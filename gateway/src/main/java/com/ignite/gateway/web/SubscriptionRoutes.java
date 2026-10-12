package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.alarming.AlarmListener;
import com.inductiveautomation.ignition.common.gson.JsonNull;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonPrimitive;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
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
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events (SSE) endpoint for real-time tag value and alarm subscriptions.
 *
 * Route:
 *   GET /sse  — open an SSE stream; subscriptions specified in query params
 *
 * Query parameters:
 *   paths   — comma-separated tag paths to subscribe to (optional)
 *             e.g. paths=[default]Folder/MyTag,[default]Folder/OtherTag
 *   alarms  — "true" to receive alarm events (default: false)
 *   path    — alarm source path prefix filter when alarms=true (optional, default = all)
 *   apiKey  — API key for auth (or use X-Ignite-Key header)
 *
 * Server event format:
 *   Tag change:  data: {"type":"tagChange","path":"...","value":...,"quality":"Good","timestamp":"..."}
 *   Alarm:       data: {"type":"alarm","source":"...","displayPath":"...","name":"...",
 *                        "state":"Active|Cleared|Acknowledged","priority":"...","timestamp":"..."}
 *
 * Auth: X-Ignite-Key header or apiKey query param matched against any site's API key.
 * An active Ignition session (non-empty actor) is also accepted.
 *
 * The connection stays open until the client disconnects or MAX_CONNECTION_MS elapses.
 * A heartbeat comment (": ping") is sent every HEARTBEAT_POLL_SEC if no events occur,
 * to keep the connection alive through proxies.
 */
public final class SubscriptionRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.SubscriptionRoutes");

    /** Maximum time a single SSE connection may stay open (30 minutes). */
    private static final long MAX_CONNECTION_MS = 30 * 60 * 1000L;

    /** How long to wait for an event before sending a heartbeat comment. */
    private static final long HEARTBEAT_POLL_SEC = 30L;

    /** Maximum queued events per connection before dropping oldest (backpressure). */
    private static final int QUEUE_CAPACITY = 500;

    private SubscriptionRoutes() {}

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
                .mount();

        log.info("SSE subscription route mounted at /sse.");
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

        // --- parse params ---
        String pathsParam = req.getParameter("paths");
        boolean wantsAlarms = "true".equalsIgnoreCase(req.getParameter("alarms"));
        String alarmPathParam = req.getParameter("path");

        List<TagPath> tagPaths = new ArrayList<>();
        if (pathsParam != null && !pathsParam.isBlank()) {
            for (String p : pathsParam.split(",")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    TagPath parsed = TagPathParser.parseSafe(trimmed);
                    if (parsed == null) {
                        throw new AuthUtil.BadRequestException("invalid tag path: " + trimmed);
                    }
                    tagPaths.add(parsed);
                }
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

        // --- per-connection event queue ---
        LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

        // --- tag listeners ---
        List<TagChangeListener> tagListeners = new ArrayList<>(tagPaths.size());
        for (TagPath tp : tagPaths) {
            final String pathStr = tp.toString();
            tagListeners.add(new TagChangeListener() {
                @Override
                public void tagChanged(TagChangeEvent event) {
                    QualifiedValue qv = event.getValue();
                    JsonObject msg = new JsonObject();
                    msg.addProperty("type", "tagChange");
                    msg.addProperty("path", pathStr);
                    msg.add("value", serializeValue(qv != null ? qv.getValue() : null));
                    msg.addProperty("quality",
                            qv != null && qv.getQuality() != null ? qv.getQuality().getName() : "Unknown");
                    Date ts = qv != null ? qv.getTimestamp() : null;
                    msg.addProperty("timestamp", ts != null ? ts.toInstant().toString()
                            : Instant.now().toString());
                    queue.offer("data: " + msg + "\n\n");
                }
            });
        }

        // --- subscribe tags ---
        GatewayTagManager tagManager = context.getTagManager();
        if (!tagPaths.isEmpty()) {
            try {
                tagManager.subscribeAsync(tagPaths, tagListeners).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Error subscribing to tags for SSE connection: " + e.getMessage(), e);
            }
        }

        // --- alarm listener ---
        AlarmListener alarmListener = null;
        if (wantsAlarms) {
            alarmListener = buildAlarmListener(queue);
            try {
                context.getAlarmManager().addListener(alarmQPath, alarmListener);
            } catch (Exception e) {
                log.warn("Error registering alarm listener for SSE connection: " + e.getMessage(), e);
            }
        }

        // --- stream events ---
        PrintWriter writer = res.getWriter();
        // Initial comment confirms stream is open
        writer.write(": connected\n\n");
        writer.flush();

        long deadline = System.currentTimeMillis() + MAX_CONNECTION_MS;
        try {
            while (System.currentTimeMillis() < deadline && !writer.checkError()) {
                String event = queue.poll(HEARTBEAT_POLL_SEC, TimeUnit.SECONDS);
                if (event != null) {
                    writer.write(event);
                } else {
                    // Heartbeat keeps proxies from closing idle connections
                    writer.write(": ping\n\n");
                }
                writer.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cleanup(tagManager, tagPaths, tagListeners, context, alarmQPath, alarmListener,
                    pathsParam);
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
                                String pathsParam) {
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
        log.debug("SSE connection closed. paths=" + pathsParam);
    }

    // -------------------------------------------------------------------------
    // Alarm listener factory
    // -------------------------------------------------------------------------

    private static AlarmListener buildAlarmListener(LinkedBlockingQueue<String> queue) {
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
                queue.offer("data: " + msg + "\n\n");
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
