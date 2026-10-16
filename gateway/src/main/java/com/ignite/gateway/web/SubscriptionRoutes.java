package com.ignite.gateway.web;

import com.ignite.gateway.SettingsUtil;
import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.alarming.AlarmListener;
import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
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
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-Sent Events (SSE) endpoint for real-time tag value and alarm subscriptions.
 *
 * Route:
 *   GET  /sse                 — open an SSE stream; subscriptions specified in query params
 *   POST /sse/subscriptions   — register a (long) subscription list, returns an id for GET /sse?subscription=
 *
 * Query parameters:
 *   paths   — comma-separated tag paths to subscribe to (optional)
 *             e.g. paths=[default]Folder/MyTag,[default]Folder/OtherTag
 *             Patterns (see {@link TagPattern}): a segment may contain "*" (one segment, or
 *             any characters inside it), the last segment may be "**" (all leaves, any depth):
 *             "[prov]Folder/*", "[prov]Folder/**", "[prov]Rooms/&#42;/DP_1/Value". They are
 *             expanded once, by tag browse, at connect time. At most the Gateway Setting
 *             maxSubscriptionTags (default 1000) tags per connection, otherwise
 *             400 {"error":"too many tags in subscription (limit N)"}.
 *   subscription — id from POST /sse/subscriptions (instead of paths/alarms/path)
 *   alarms  — "true" to receive alarm events (default: false)
 *   path    — alarm source path prefix filter when alarms=true (optional, default = all)
 *   apiKey  — API key for auth (or use X-Ignite-Key header)
 *
 * Server event format:
 *   Initial snapshot: one tagChange per subscribed tag with its current value, right after connect
 *                     (the subscription's own initial callback is suppressed: no duplicate).
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
 * Capacity: at most the Gateway Setting maxSseClients (default 64, read per connect) concurrent
 * streams; beyond that 503 {"error":"too many live connections"}. The route semaphore is sized
 * at mount time (max(setting, 64) + headroom), so raising the setting past the mount-time value
 * needs a module restart. Each stream holds one servlet thread. A stream ends when the client
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
     * Concurrent SSE streams are limited by our own counter (JSON 503) against the Gateway Setting
     * maxSseClients, read per connect. The gateway's per-route Semaphore (default 5 permits, 2 s wait)
     * is raised above the cap so ours trips first; it is fixed at mount time to
     * max(setting at mount, ROUTE_MIN_CLIENTS) + ROUTE_PERMIT_HEADROOM.
     */
    private static final int ROUTE_MIN_CLIENTS = 64;

    /** Headroom on the gateway route semaphore over the client cap (rejected requests briefly hold a permit). */
    private static final int ROUTE_PERMIT_HEADROOM = 16;

    /** How long a registered subscription stays valid after its last use. */
    private static final long REGISTRATION_TTL_MS = 60_000L;

    /** Upper bound on live registrations (each may hold up to maxSubscriptionTags paths). */
    private static final int MAX_REGISTRATIONS = 256;

    /** Timeout for browse / subscribe / read calls. */
    private static final long TAG_TIMEOUT_SEC = 10L;

    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final Set<Conn> STREAMS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Registered> REGISTERED = new ConcurrentHashMap<>();
    private static ScheduledExecutorService sweeper;

    /** A subscription list registered by POST /sse/subscriptions: expanded, scope-checked, bound to one site and credential. */
    private static final class Registered {
        final String site;
        final String binding;
        final List<String> paths;
        final boolean alarms;
        final String alarmPath;
        volatile long expiresAt;

        Registered(String site, String binding, Resolved r) {
            this.site = site;
            this.binding = binding;
            this.paths = r.paths;
            this.alarms = r.alarms;
            this.alarmPath = r.alarmPath;
            this.expiresAt = System.currentTimeMillis() + REGISTRATION_TTL_MS;
        }
    }

    /** Expanded subscription: tag path strings plus the alarm request. */
    private static final class Resolved {
        final List<String> paths;
        final boolean alarms;
        final String alarmPath;

        Resolved(List<String> paths, boolean alarms, String alarmPath) {
            this.paths = paths;
            this.alarms = alarms;
            this.alarmPath = alarmPath;
        }
    }

    /** A requested path lies outside the site's tag scope (maps to 403). */
    private static final class ScopeDenied extends Exception {
        final String path;

        ScopeDenied(String path) {
            super(path, null, false, false);
            this.path = path;
        }
    }

    private static void sweepRegistrations() {
        long now = System.currentTimeMillis();
        REGISTERED.values().removeIf(r -> r.expiresAt < now);
    }

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
        synchronized (REGISTERED) {
            if (sweeper != null) {
                sweeper.shutdownNow();
                sweeper = null;
            }
        }
        REGISTERED.clear();
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

        int configured = SettingsUtil.maxSseClients(context);
        int routeClients = Math.max(configured, ROUTE_MIN_CLIENTS);

        // GET /sse — SSE subscription stream
        routes.newRoute("/sse")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_PLAIN_TEXT)   // overridden to text/event-stream in handler
                .handler((req, res) -> guarded(req, res, () -> {
                    streamSse(req, res, context);
                    return null;
                }))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .concurrency(routeClients + ROUTE_PERMIT_HEADROOM, 0)
                .mount();

        // POST /sse/subscriptions — register a subscription list (expansion + scope checks happen here)
        routes.newRoute("/sse/subscriptions")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> registerSubscription(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        synchronized (REGISTERED) {
            if (sweeper == null) {
                sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "ignite-sse-registration-sweeper");
                    t.setDaemon(true);
                    return t;
                });
                sweeper.scheduleWithFixedDelay(SubscriptionRoutes::sweepRegistrations, 15, 15, TimeUnit.SECONDS);
            }
        }

        log.info("SSE subscription route mounted at /sse (max " + configured + " clients, route permits sized for "
                + routeClients + "); POST /sse/subscriptions mounted.");
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
        if (ACTIVE.incrementAndGet() > SettingsUtil.maxSseClients(context)) {
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
        String subscriptionParam = req.getParameter("subscription");
        String pathsParam = req.getParameter("paths");
        Resolved resolved;
        if (subscriptionParam != null && !subscriptionParam.isBlank()) {
            if (pathsParam != null && !pathsParam.isBlank()) {
                throw new AuthUtil.BadRequestException("subscription cannot be combined with paths");
            }
            resolved = lookupRegistered(req, res, context, site, scope, subscriptionParam.trim());
            if (resolved == null) {
                return;
            }
            pathsParam = "subscription:" + subscriptionParam.trim();
        } else {
            List<String> entries = new ArrayList<>();
            if (pathsParam != null) {
                for (String p : pathsParam.split(",")) {
                    entries.add(p);
                }
            }
            try {
                resolved = resolve(tagManager, scope, SettingsUtil.maxSubscriptionTags(context), entries,
                        "true".equalsIgnoreCase(req.getParameter("alarms")), req.getParameter("path"));
            } catch (ScopeDenied d) {
                denyScope(res, site, d.path);
                return;
            }
        }
        boolean wantsAlarms = resolved.alarms;
        QualifiedPath alarmQPath = resolved.alarms ? alarmQualifiedPath(resolved.alarmPath) : null;
        List<TagPath> tagPaths = new ArrayList<>(resolved.paths.size());
        for (String s : resolved.paths) {
            TagPath tp = TagPathParser.parseSafe(s);
            if (tp != null) {
                tagPaths.add(tp);
            }
        }

        // --- SSE response headers ---
        res.setContentType("text/event-stream");
        res.setCharacterEncoding("UTF-8");
        res.setHeader("Cache-Control", "no-cache, no-store");
        res.setHeader("X-Accel-Buffering", "no"); // disable Nginx response buffering
        res.setHeader("Connection", "keep-alive");

        // --- tag listeners ---
        // the snapshot is authoritative: it is read before subscribing, and the subscription's
        // initial callback (a repeat of it) is dropped by SnapshotDedupe
        SnapshotDedupe dedupe = new SnapshotDedupe();
        List<QualifiedValue> snapshotValues = null;
        if (!tagPaths.isEmpty()) {
            try {
                snapshotValues = tagManager.readAsync(tagPaths).get(TAG_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Error reading initial snapshot for SSE connection: " + e.getMessage(), e);
            }
            if (snapshotValues != null) {
                for (int i = 0; i < tagPaths.size() && i < snapshotValues.size(); i++) {
                    dedupe.record(tagPaths.get(i).toString(), snapshotValues.get(i));
                }
            }
        }

        List<TagChangeListener> tagListeners = new ArrayList<>(tagPaths.size());
        for (TagPath tp : tagPaths) {
            final String pathStr = tp.toString();
            tagListeners.add(new TagChangeListener() {
                @Override
                public void tagChanged(TagChangeEvent event) {
                    if (dedupe.shouldDrop(pathStr, event.getValue())) {
                        return;
                    }
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
            if (snapshotValues != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tagPaths.size() && i < snapshotValues.size(); i++) {
                    sb.append(tagChangeEvent(tagPaths.get(i).toString(), snapshotValues.get(i)));
                }
                if (sb.length() > 0 && !send(writer, sb.toString())) {
                    return;
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

    // -------------------------------------------------------------------------
    // Registration (POST /sse/subscriptions)
    // -------------------------------------------------------------------------

    private static Object registerSubscription(RequestContext req, HttpServletResponse res,
                                               GatewayContext context) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }
        JsonObject body = AuthUtil.parseJsonObject(req);
        List<String> entries = new ArrayList<>();
        JsonElement pathsEl = body.get("paths");
        if (pathsEl != null && !pathsEl.isJsonNull()) {
            if (!pathsEl.isJsonArray()) {
                throw new AuthUtil.BadRequestException("paths must be an array of strings");
            }
            for (JsonElement el : (JsonArray) pathsEl) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                    throw new AuthUtil.BadRequestException("paths must be an array of strings");
                }
                entries.add(el.getAsString());
            }
        }
        JsonElement alarmsEl = body.get("alarms");
        boolean alarms = alarmsEl != null && alarmsEl.isJsonPrimitive() && alarmsEl.getAsBoolean();
        JsonElement pathEl = body.get("path");
        String alarmPath = pathEl != null && pathEl.isJsonPrimitive() ? pathEl.getAsString() : null;

        sweepRegistrations();
        if (REGISTERED.size() >= MAX_REGISTRATIONS) {
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return AuthUtil.errorJson("too many registered subscriptions");
        }
        Resolved resolved;
        try {
            resolved = resolve(context.getTagManager(), TagScope.forSite(site),
                    SettingsUtil.maxSubscriptionTags(context), entries, alarms, alarmPath);
        } catch (ScopeDenied d) {
            return TagScope.deny(res, log, site, d.path);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        REGISTERED.put(id, new Registered(site.getName(), credentialBinding(req, context), resolved));

        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("count", resolved.paths.size());
        out.addProperty("expiresInMs", REGISTRATION_TTL_MS);
        return out;
    }

    /**
     * Looks up a registered subscription for GET /sse?subscription=; refreshes its expiry. Writes the
     * error response and returns null when unknown/expired (404) or bound to another site or credential (403).
     */
    private static Resolved lookupRegistered(RequestContext req, HttpServletResponse res, GatewayContext context,
                                             SiteRecord site, TagScope scope, String id) throws Exception {
        Registered r = REGISTERED.get(id);
        if (r == null || r.expiresAt < System.currentTimeMillis()) {
            REGISTERED.remove(id);
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.setContentType("application/json");
            res.getWriter().write(AuthUtil.errorJson("subscription not found or expired").toString());
            return null;
        }
        if (!r.site.equals(site.getName()) || !r.binding.equals(credentialBinding(req, context))) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.getWriter().write(AuthUtil.errorJson("subscription belongs to another client").toString());
            return null;
        }
        if (!scope.isUnrestricted()) {
            for (String p : r.paths) {
                if (!scope.allows(p)) {
                    denyScope(res, site, p);
                    return null;
                }
            }
        }
        r.expiresAt = System.currentTimeMillis() + REGISTRATION_TTL_MS;
        return new Resolved(r.paths, r.alarms, r.alarmPath);
    }

    /** Credential kind plus actor: a registered id may only be used by the credential that registered it. */
    private static String credentialBinding(RequestContext req, GatewayContext context) {
        String kind = AuthUtil.extractBearerToken(req) != null ? "bearer"
                : AuthUtil.webSessionUser(req).isPresent() ? "session" : "key";
        return kind + ":" + AuthUtil.getActorIdentity(req, context);
    }

    private static QualifiedPath alarmQualifiedPath(String alarmPath) {
        return (alarmPath != null && !alarmPath.isBlank())
                ? QualifiedPath.parseSafe(alarmPath)
                : QualifiedPath.newBuilder().build();
    }

    // -------------------------------------------------------------------------
    // Resolution: entries to expanded, scope-checked tag paths
    // -------------------------------------------------------------------------

    /**
     * Expands subscription entries (literal paths, folder/segment patterns) against the tag browse,
     * enforcing the site scope and the tag cap; validates the alarm path. A blank list is allowed.
     */
    private static Resolved resolve(GatewayTagManager tagManager, TagScope scope, int cap,
                                    List<String> entries, boolean wantsAlarms, String alarmPathParam)
            throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        boolean any = false;
        for (String p : entries) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            any = true;
            if (trimmed.indexOf('*') >= 0) {
                TagPattern pat = TagPattern.parse(trimmed);
                if (pat == null) {
                    throw new AuthUtil.BadRequestException("invalid tag pattern: " + trimmed);
                }
                String prefix = pat.prefixPath();
                TagPath prefixPath = TagPathParser.parseSafe(prefix);
                if (prefixPath == null) {
                    throw new AuthUtil.BadRequestException("invalid tag pattern: " + trimmed);
                }
                if (!scope.allowsFolderBrowse(prefixPath)) {
                    throw new ScopeDenied(trimmed);
                }
                expandPattern(tagManager, pat, prefixPath, prefix, pat.prefixLength(), seen, scope, cap, true);
            } else {
                TagPath parsed = TagPathParser.parseSafe(trimmed);
                if (parsed == null) {
                    throw new AuthUtil.BadRequestException("invalid tag path: " + trimmed);
                }
                if (!scope.allows(parsed)) {
                    throw new ScopeDenied(trimmed);
                }
                if (seen.add(parsed.toString())) {
                    checkCap(seen.size(), cap);
                }
            }
        }
        if (any && !scope.isUnrestricted() && seen.isEmpty()) {
            throw new ScopeDenied(String.join(",", entries));
        }
        String alarmPath = null;
        if (wantsAlarms) {
            if (alarmQualifiedPath(alarmPathParam) == null) {
                throw new AuthUtil.BadRequestException("invalid alarm path: " + alarmPathParam);
            }
            alarmPath = alarmPathParam;
        }
        return new Resolved(new ArrayList<>(seen), wantsAlarms, alarmPath);
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

    private static void checkCap(int size, int cap) {
        if (size > cap) {
            throw new AuthUtil.BadRequestException("too many tags in subscription (limit " + cap + ")");
        }
    }

    /**
     * Browses {@code folder} (at {@code depth} below the provider) and adds the leaf (atomic) tag
     * paths selected by {@code pat}; descends only into folders / UDT instances whose name matches
     * the next segment. Literal next segments are entered directly without browsing the parent.
     * Same provider/path syntax as /tags/browse. {@code mustExist} (the starting folder only)
     * turns a missing folder into 400; below it, missing folders are skipped.
     */
    private static void expandPattern(GatewayTagManager tagManager, TagPattern pat, TagPath folder,
                                      String folderStr, int depth, Set<String> out, TagScope scope,
                                      int cap, boolean mustExist) throws Exception {
        while (pat.isFolderPosition(depth) && pat.isLiteralAt(depth)) {
            folderStr = folderStr + (folderStr.endsWith("]") ? "" : "/") + pat.segmentAt(depth);
            folder = TagPathParser.parseSafe(folderStr);
            depth++;
            if (folder == null || !scope.allowsFolderBrowse(folder)) {
                return;
            }
        }
        Results<NodeDescription> results = tagManager.browseAsync(folder, BrowseFilter.NONE)
                .get(TAG_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (results == null || results.getResults() == null) {
            // the browse of a non-existent folder/provider returns no result list
            if (mustExist) {
                throw new AuthUtil.BadRequestException("tag folder not found: " + folderStr);
            }
            return;
        }
        for (NodeDescription node : results.getResults()) {
            TagPath full = node.getFullPath();
            String childStr = (full != null && !full.toString().isEmpty())
                    ? full.toString()
                    : folderStr + (folderStr.endsWith("]") ? "" : "/") + node.getName();
            TagObjectType type = node.getObjectType();
            if (type == TagObjectType.AtomicTag) {
                if (pat.matchesLeaf(depth, node.getName()) && scope.allows(childStr) && out.add(childStr)) {
                    checkCap(out.size(), cap);
                }
            } else if (node.hasChildren()
                    && (type == TagObjectType.Folder || type == TagObjectType.UdtInstance)
                    && pat.matchesFolder(depth, node.getName())) {
                TagPath childPath = full != null && !full.toString().isEmpty()
                        ? full : TagPathParser.parseSafe(childStr);
                if (childPath != null && scope.allowsFolderBrowse(childPath)) {
                    expandPattern(tagManager, pat, childPath, childStr, depth + 1, out, scope, cap, false);
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
