package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.alarming.AlarmListener;
import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.gson.JsonNull;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonPrimitive;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeEvent;
import com.inductiveautomation.ignition.common.tags.model.event.TagChangeListener;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import jakarta.servlet.http.HttpServletResponse;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-Sent Events (SSE) endpoint for real-time tag value and alarm subscriptions.
 *
 * Route:
 *   GET /sse  — open an SSE stream; subscriptions specified in query params
 *
 * Query parameters:
 *   paths   — comma-separated tag paths to subscribe to (optional)
 *             e.g. paths=[default]Folder/MyTag,[default]Folder/OtherTag
 *             Folder entries: "[prov]Folder/*" subscribes to the direct leaf (atomic) tags of
 *             Folder; "[prov]Folder/**" subscribes to all leaf tags recursively. They are
 *             expanded once, by tag browse, at connect time. At most MAX_EXPANDED_TAGS (1000)
 *             tags per connection, otherwise 400 {"error":"too many tags in subscription"}.
 *   alarms  — "true" to receive alarm events (default: false)
 *   path    — alarm source path prefix filter when alarms=true (optional, default = all)
 *   apiKey  — API key for auth (or use X-Ignite-Key header)
 *
 * Server event format:
 *   Initial snapshot: one tagChange per subscribed tag with its current value, right after connect.
 *   Tag change:  data: {"type":"tagChange","path":"...","value":...,"quality":"Good","timestamp":"..."}
 *   Alarm:       data: {"type":"alarm","source":"...","displayPath":"...","name":"...",
 *                        "state":"Active|Cleared|Acknowledged","priority":"...","timestamp":"..."}
 *   Subscribed:  event: subscribed / data: {"type":"subscribed","count":N}   (after the snapshot)
 *   Reconnect:   event: reconnect / data: {"type":"reconnect"}   (just before the server closes)
 *   Heartbeat:   ": ping" comment every HEARTBEAT_POLL_SEC when idle
 *
 * Tag scope (ADR-004 A1): explicit paths outside the site's TagPathPatterns give 403
 * {"error":"tag path not permitted"}; folder patterns are expanded then filtered, "subscribed"
 * counts the survivors (none = 403); alarm events are filtered by source.
 *
 * Auth: X-Ignite-Key header or apiKey query param matched against any site's API key.
 * An active Ignition session (non-empty actor) is also accepted.
 *
 * Capacity: at most MAX_SSE_CLIENTS concurrent streams; beyond that 503 {"error":"too many
 * live connections"}. Each stream holds one servlet thread. A stream ends when the client
 * disconnects (detected on the next write; heartbeat every 10 s), after MAX_CONNECTION_MS, or
 * when the module shuts down ({@link #shutdownAll()}).
 */
public final class SubscriptionRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.SubscriptionRoutes");

    /** Maximum time a single SSE connection may stay open (20 minutes). */
    private static final long MAX_CONNECTION_MS = 20 * 60 * 1000L;

    /** How long to wait for an event before sending a heartbeat comment (also dead-client probe). */
    private static final long HEARTBEAT_POLL_SEC = 10L;

    /** Maximum queued events per connection before dropping oldest (backpressure). */
    private static final int QUEUE_CAPACITY = 500;

    /**
     * Maximum concurrent SSE streams, enforced by our own counter (JSON 503). The gateway's
     * per-route Semaphore (default 5 permits, 2 s wait) is raised above this so ours trips first.
     */
    private static final int MAX_SSE_CLIENTS = 64;

    /** Headroom on the gateway route semaphore over MAX_SSE_CLIENTS (rejected requests briefly hold a permit). */
    private static final int ROUTE_PERMIT_HEADROOM = 16;

    /** Maximum tag paths per connection after folder expansion. */
    private static final int MAX_EXPANDED_TAGS = 1000;

    /** Timeout for browse / subscribe / read calls. */
    private static final long TAG_TIMEOUT_SEC = 10L;

    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final Set<Conn> STREAMS = ConcurrentHashMap.newKeySet();

    private SubscriptionRoutes() {}

    /** Per-connection state: bounded event queue (drop-oldest) and drop counter. */
    private static final class Conn {
        final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        final AtomicLong drops = new AtomicLong();
        final Thread thread = Thread.currentThread();
        volatile boolean stop;

        void enqueue(String event) {
            while (!queue.offer(event)) {
                if (queue.poll() != null) {
                    drops.incrementAndGet();
                }
            }
        }
    }

    /** Ends every open stream (module shutdown) so no servlet threads or slots stay occupied. */
    public static void shutdownAll() {
        for (Conn c : STREAMS) {
            c.stop = true;
            c.thread.interrupt();
        }
    }

    /** Handler body that may throw; run via {@link #guarded}. */
    private interface Handler {
        Object run() throws Exception;
    }

    /**
     * Runs a handler body: BadRequestException maps to 400, anything else to a generic 500
     * (details are logged server-side under a ref). Specific mappings stay in the body.
     */
    private static Object guarded(RequestContext req, HttpServletResponse res, Handler h) {
        try {
            return h.run();
        } catch (AuthUtil.BadRequestException e) {
            return AuthUtil.badRequest(res, e.getMessage());
        } catch (Exception e) {
            return AuthUtil.internalError(res, log,
                    req.getRequest().getMethod() + " " + req.getRequest().getRequestURI(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Route registration
    // -------------------------------------------------------------------------

    public static void mountRoutes(RouteGroup routes, GatewayContext context) {

        // GET /sse — SSE subscription stream
        routes.newRoute("/sse")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_PLAIN_TEXT)   // overridden to text/event-stream in handler
                .handler((req, res) -> guarded(req, res, () -> {
                    streamSse(req, res, context);
                    return null;
                }))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .concurrency(MAX_SSE_CLIENTS + ROUTE_PERMIT_HEADROOM, 0)
                .mount();

        log.info("SSE subscription route mounted at /sse (max " + MAX_SSE_CLIENTS + " clients).");
    }

    // -------------------------------------------------------------------------
    // SSE handler
    // -------------------------------------------------------------------------

    /**
     * Open an SSE stream for the requesting client.
     *
     * Blocks the handler thread until the client disconnects or the max connection
     * time is reached, then cleans up all subscriptions.
     */
    private static void streamSse(RequestContext req, HttpServletResponse res,
                                   GatewayContext context) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Unauthorized\"}");
            return;
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.getWriter().write(AuthUtil.errorJson("site required: send X-Ignite-Site or an API key").toString());
            return;
        }

        // --- claim a slot (released exactly once in the finally below) ---
        if (ACTIVE.incrementAndGet() > MAX_SSE_CLIENTS) {
            ACTIVE.decrementAndGet();
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            res.setContentType("application/json");
            res.getWriter().write(AuthUtil.errorJson("too many live connections").toString());
            return;
        }
        Conn conn = new Conn();
        STREAMS.add(conn);
        try {
            runStream(req, res, context, conn, site);
        } finally {
            STREAMS.remove(conn);
            ACTIVE.decrementAndGet();
        }
    }

    private static void runStream(RequestContext req, HttpServletResponse res,
                                  GatewayContext context, Conn conn, SiteRecord site) throws Exception {
        TagScope scope = TagScope.forSite(site);
        GatewayTagManager tagManager = context.getTagManager();

        // --- parse params ---
        String pathsParam = req.getParameter("paths");
        boolean wantsAlarms = "true".equalsIgnoreCase(req.getParameter("alarms"));
        String alarmPathParam = req.getParameter("path");

        List<TagPath> tagPaths = new ArrayList<>();
        if (pathsParam != null && !pathsParam.isBlank()) {
            Set<String> seen = new LinkedHashSet<>();
            for (String p : pathsParam.split(",")) {
                String trimmed = p.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.endsWith("/**") || trimmed.endsWith("/*")) {
                    boolean recursive = trimmed.endsWith("/**");
                    String base = trimmed.substring(0, trimmed.length() - (recursive ? 3 : 2));
                    TagPath baseParsed = TagPathParser.parseSafe(base);
                    if (baseParsed == null) {
                        throw new AuthUtil.BadRequestException("invalid tag path: " + trimmed);
                    }
                    if (!scope.allowsFolderBrowse(baseParsed)) {
                        denyScope(res, site, trimmed);
                        return;
                    }
                    expandFolder(tagManager, baseParsed, base, recursive, seen, scope);
                } else {
                    TagPath parsed = TagPathParser.parseSafe(trimmed);
                    if (parsed == null) {
                        throw new AuthUtil.BadRequestException("invalid tag path: " + trimmed);
                    }
                    if (!scope.allows(parsed)) {
                        denyScope(res, site, trimmed);
                        return;
                    }
                    if (seen.add(parsed.toString())) {
                        checkCap(seen.size());
                    }
                }
            }
            for (String s : seen) {
                TagPath tp = TagPathParser.parseSafe(s);
                if (tp != null) {
                    tagPaths.add(tp);
                }
            }
            if (!scope.isUnrestricted() && tagPaths.isEmpty()) {
                denyScope(res, site, pathsParam);
                return;
            }
        }

        QualifiedPath alarmQPath = null;
        if (wantsAlarms) {
            alarmQPath = (alarmPathParam != null && !alarmPathParam.isBlank())
                    ? QualifiedPath.parseSafe(alarmPathParam)
                    : QualifiedPath.newBuilder().build();
            if (alarmQPath == null) {
                throw new AuthUtil.BadRequestException("invalid alarm path: " + alarmPathParam);
            }
        }

        // --- SSE response headers ---
        res.setContentType("text/event-stream");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-cache, no-store");
        res.setHeader("X-Accel-Buffering", "no"); // disable Nginx response buffering
        res.setHeader("Connection", "keep-alive");

        // --- tag listeners ---
        List<TagChangeListener> tagListeners = new ArrayList<>(tagPaths.size());
        for (TagPath tp : tagPaths) {
            final String pathStr = tp.toString();
            tagListeners.add(new TagChangeListener() {
                @Override
                public void tagChanged(TagChangeEvent event) {
                    conn.enqueue(tagChangeEvent(pathStr, event.getValue()));
                }
            });
        }

        AlarmListener alarmListener = null;
        PrintWriter writer = res.getWriter();
        try {
            // --- subscribe tags ---
            if (!tagPaths.isEmpty()) {
                try {
                    tagManager.subscribeAsync(tagPaths, tagListeners).get(TAG_TIMEOUT_SEC, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Error subscribing to tags for SSE connection: " + e.getMessage(), e);
                }
            }

            // --- alarm listener ---
            if (wantsAlarms) {
                alarmListener = buildAlarmListener(conn, scope);
                try {
                    context.getAlarmManager().addListener(alarmQPath, alarmListener);
                } catch (Exception e) {
                    log.warn("Error registering alarm listener for SSE connection: " + e.getMessage(), e);
                }
            }

            // Initial comment confirms stream is open
            if (!send(writer, ": connected\n\n")) {
                return;
            }

            // --- initial snapshot (written directly: may exceed the queue capacity) ---
            if (!tagPaths.isEmpty()) {
                List<QualifiedValue> values = null;
                try {
                    values = tagManager.readAsync(tagPaths).get(TAG_TIMEOUT_SEC, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Error reading initial snapshot for SSE connection: " + e.getMessage(), e);
                }
                if (values != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < tagPaths.size() && i < values.size(); i++) {
                        sb.append(tagChangeEvent(tagPaths.get(i).toString(), values.get(i)));
                    }
                    if (sb.length() > 0 && !send(writer, sb.toString())) {
                        return;
                    }
                }
            }
            if (!send(writer, "event: subscribed\ndata: {\"type\":\"subscribed\",\"count\":"
                    + tagPaths.size() + "}\n\n")) {
                return;
            }

            // --- stream events ---
            long deadline = System.currentTimeMillis() + MAX_CONNECTION_MS;
            try {
                while (!conn.stop && System.currentTimeMillis() < deadline) {
                    String event = conn.queue.poll(HEARTBEAT_POLL_SEC, TimeUnit.SECONDS);
                    // Heartbeat keeps proxies from closing idle connections and probes dead clients
                    if (!send(writer, event != null ? event : ": ping\n\n")) {
                        return;
                    }
                }
                if (!conn.stop) {
                    send(writer, "event: reconnect\ndata: {\"type\":\"reconnect\"}\n\n");
                }
            } catch (InterruptedException e) {
                // shutdownAll() or container interrupt: end the stream; flag cleared so cleanup can block
                Thread.interrupted();
            }
        } finally {
            cleanup(tagManager, tagPaths, tagListeners, context, alarmQPath, alarmListener,
                    pathsParam, conn);
        }
    }

    /** Writes the 403 "tag path not permitted" JSON body (stream not opened). */
    private static void denyScope(HttpServletResponse res, SiteRecord site, Object path) throws java.io.IOException {
        res.setContentType("application/json");
        res.getWriter().write(TagScope.deny(res, log, site, path).toString());
    }

    /** Writes and flushes; false when the client is gone (PrintWriter swallows IOException/EofException). */
    private static boolean send(PrintWriter writer, String data) {
        try {
            writer.write(data);
            writer.flush();
            return !writer.checkError();
        } catch (Exception e) {
            return false;
        }
    }

    private static String tagChangeEvent(String pathStr, QualifiedValue qv) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "tagChange");
        msg.addProperty("path", pathStr);
        msg.add("value", serializeValue(qv != null ? qv.getValue() : null));
        msg.addProperty("quality",
                qv != null && qv.getQuality() != null ? qv.getQuality().getName() : "Unknown");
        Date ts = qv != null ? qv.getTimestamp() : null;
        msg.addProperty("timestamp", ts != null ? ts.toInstant().toString()
                : Instant.now().toString());
        return "data: " + msg + "\n\n";
    }

    // -------------------------------------------------------------------------
    // Folder expansion
    // -------------------------------------------------------------------------

    private static void checkCap(int size) {
        if (size > MAX_EXPANDED_TAGS) {
            throw new AuthUtil.BadRequestException("too many tags in subscription");
        }
    }

    /**
     * Browses a folder and adds its leaf (atomic) tag paths to {@code out}; folders and UDT
     * instances are descended into when {@code recursive}. Same provider/path syntax as /tags/browse.
     */
    private static void expandFolder(GatewayTagManager tagManager, TagPath folder, String folderStr,
                                     boolean recursive, Set<String> out, TagScope scope)
            throws Exception {
        Results<NodeDescription> results = tagManager.browseAsync(folder, BrowseFilter.NONE)
                .get(TAG_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (results == null || results.getResults() == null) {
            // the browse of a non-existent folder/provider returns no result list
            throw new AuthUtil.BadRequestException("tag folder not found: " + folderStr);
        }
        for (NodeDescription node : results.getResults()) {
            TagPath full = node.getFullPath();
            String childStr = (full != null && !full.toString().isEmpty())
                    ? full.toString()
                    : folderStr + "/" + node.getName();
            TagObjectType type = node.getObjectType();
            if (type == TagObjectType.AtomicTag) {
                if (scope.allows(childStr) && out.add(childStr)) {
                    checkCap(out.size());
                }
            } else if (recursive && node.hasChildren()
                    && (type == TagObjectType.Folder || type == TagObjectType.UdtInstance)) {
                TagPath childPath = full != null && !full.toString().isEmpty()
                        ? full : TagPathParser.parseSafe(childStr);
                if (childPath != null && scope.allowsFolderBrowse(childPath)) {
                    expandFolder(tagManager, childPath, childStr, true, out, scope);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    private static void cleanup(GatewayTagManager tagManager,
                                List<TagPath> tagPaths,
                                List<TagChangeListener> tagListeners,
                                GatewayContext context,
                                QualifiedPath alarmQPath,
                                AlarmListener alarmListener,
                                String pathsParam,
                                Conn conn) {
        if (!tagPaths.isEmpty()) {
            try {
                tagManager.unsubscribeAsync(tagPaths, tagListeners).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Error unsubscribing tags on SSE disconnect.", e);
            }
        }
        if (alarmListener != null && alarmQPath != null) {
            try {
                context.getAlarmManager().removeListener(alarmQPath, alarmListener);
            } catch (Exception e) {
                log.warn("Error removing alarm listener on SSE disconnect.", e);
            }
        }
        log.debug("SSE connection closed. paths=" + pathsParam + " dropped=" + conn.drops.get());
    }

    // -------------------------------------------------------------------------
    // Alarm listener factory
    // -------------------------------------------------------------------------

    private static AlarmListener buildAlarmListener(Conn conn, TagScope scope) {
        return new AlarmListener() {
            @Override
            public void onActive(AlarmEvent event) {
                enqueue("Active", event);
            }

            @Override
            public void onClear(AlarmEvent event) {
                enqueue("Cleared", event);
            }

            @Override
            public void onAcknowledge(AlarmEvent event) {
                enqueue("Acknowledged", event);
            }

            @Override
            public boolean includeInitialEvents() {
                return true;
            }

            private void enqueue(String state, AlarmEvent event) {
                if (!scope.allowsAlarmSource(event.getSource() != null ? event.getSource().toString() : null)) {
                    return;
                }
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "alarm");
                msg.addProperty("source",
                        event.getSource() != null ? event.getSource().toString() : null);
                msg.addProperty("displayPath", event.getDisplayPathOrSource());
                msg.addProperty("name", event.getName());
                msg.addProperty("label", event.getLabel());
                msg.addProperty("state", state);
                msg.addProperty("priority",
                        event.getPriority() != null ? event.getPriority().toString() : null);
                msg.addProperty("isAcked", event.isAcked());
                msg.addProperty("isCleared", event.isCleared());
                msg.addProperty("timestamp", Instant.now().toString());
                conn.enqueue("data: " + msg + "\n\n");
            }
        };
    }

    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
    // Value serialization (mirrors TagRoutes.serializeValue)
    // -------------------------------------------------------------------------

    private static com.inductiveautomation.ignition.common.gson.JsonElement serializeValue(
            Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Date) {
            return new JsonPrimitive(((Date) value).toInstant().toString());
        }
        return new JsonPrimitive(value.toString());
    }
}
