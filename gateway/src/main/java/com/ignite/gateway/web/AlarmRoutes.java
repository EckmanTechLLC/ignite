package com.ignite.gateway.web;

import com.ignite.gateway.SettingsUtil;
import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.WellKnownPathTypes;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.alarming.AlarmFilter;
import com.inductiveautomation.ignition.common.alarming.AlarmState;
import com.inductiveautomation.ignition.common.alarming.EventData;
import com.inductiveautomation.ignition.common.alarming.config.CommonAlarmProperties;
import com.inductiveautomation.ignition.common.alarming.query.AlarmQueryResult;
import com.inductiveautomation.ignition.common.alarming.query.conditions.DateTimeCondition;
import com.inductiveautomation.ignition.common.alarming.query.conditions.EnumCondition;
import com.inductiveautomation.ignition.common.alarming.query.conditions.PathCondition;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * REST route handlers for Ignition alarm operations.
 *
 * Routes are mounted under the module's route group (alias "ignite"):
 *   GET /alarms/status?path=...
 *       — current alarm states (active/cleared/acked). Optional path filter.
 *
 *   GET /alarms/history?start=ISO&end=ISO&path=...&state=...&journal=...
 *       — alarm journal events for a time range. All params optional.
 *       - start/end:  ISO-8601 timestamps (default: last 24 hours)
 *       - path:       alarm source path prefix to filter on
 *       - state:      one of ClearUnacked, ClearAcked, ActiveUnacked, ActiveAcked
 *       - journal:    alarm journal profile name (default: first available or empty)
 *
 * Auth: X-Ignite-Key header or apiKey query param matched against any site's
 * configured API key. An active Ignition session (non-empty actor) is also accepted.
 *
 * Tag scope (ADR-004 A1): status/history rows are filtered by source tag path; acknowledging an
 * alarm whose source is outside the site's TagPathPatterns gives 403 {"error":"tag path not permitted"}
 * before any ack. Sources look like "prov:default:/tag:Folder/Tag" and map to "[default]Folder/Tag".
 */
public final class AlarmRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.AlarmRoutes");

    private AlarmRoutes() {}

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

        // GET /alarms/status?path=...
        routes.newRoute("/alarms/status")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> queryAlarmStatus(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // GET /alarms/history?start=...&end=...&path=...&state=...&journal=...
        routes.newRoute("/alarms/history")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> queryAlarmHistory(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // POST /alarms/acknowledge   body: { "ids": ["uuid1", "uuid2", ...] }
        routes.newRoute("/alarms/acknowledge")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> acknowledgeAlarms(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /**
     * Return current alarm states.
     *
     * Query params:
     *   path — optional source path prefix (e.g. "prov:default:/Folder/Tag")
     */
    private static Object queryAlarmStatus(RequestContext req, HttpServletResponse res,
                                           GatewayContext context) {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }

        try {
            AlarmFilter filter = new AlarmFilter();

            String pathParam = req.getParameter("path");
            if (pathParam != null && !pathParam.isBlank()) {
                filter.and(AlarmFilter.SOURCE,
                        new PathCondition<>(parseAlarmPath(pathParam)));
            }

            AlarmQueryResult results = context.getAlarmManager().queryStatus(filter);

            TagScope scope = TagScope.forSite(site);
            JsonArray alarms = new JsonArray();
            for (AlarmEvent event : results) {
                if (!inScope(scope, event)) {
                    continue;
                }
                alarms.add(serializeEvent(event));
            }

            JsonObject result = new JsonObject();
            result.add("alarms", alarms);
            result.addProperty("count", alarms.size());
            return result;

        } catch (AuthUtil.BadRequestException e) {
            throw e;
        } catch (Exception e) {
            return AuthUtil.internalError(res, log, "Error querying alarm status", e);
        }
    }

    /**
     * Return alarm journal events for a time range.
     *
     * Query params:
     *   start   — ISO-8601 start time (default: 24 hours ago)
     *   end     — ISO-8601 end time (default: now)
     *   path    — optional source path prefix filter
     *   state   — optional state filter: ClearUnacked, ClearAcked, ActiveUnacked, ActiveAcked
     *   journal — alarm journal profile name (required; 400 if missing or blank)
     */
    private static Object queryAlarmHistory(RequestContext req, HttpServletResponse res,
                                            GatewayContext context) {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }

        String journal = req.getParameter("journal");
        if (journal == null || journal.isBlank()) {
            throw new AuthUtil.BadRequestException("journal parameter required");
        }

        try {
            // Time range
            String startStr = req.getParameter("start");
            String endStr   = req.getParameter("end");
            Date endDate   = endStr   != null ? Date.from(parseInstant(endStr, "end"))   : new Date();
            Date startDate = startStr != null ? Date.from(parseInstant(startStr, "start"))
                    : new Date(endDate.getTime() - 86_400_000L);

            AlarmFilter filter = new AlarmFilter();
            filter.and(AlarmFilter.EVENTTIME, new DateTimeCondition(startDate, endDate));

            // Optional path filter
            String pathParam = req.getParameter("path");
            if (pathParam != null && !pathParam.isBlank()) {
                filter.and(AlarmFilter.SOURCE,
                        new PathCondition<>(parseAlarmPath(pathParam)));
            }

            // Optional state filter
            String stateParam = req.getParameter("state");
            if (stateParam != null && !stateParam.isBlank()) {
                AlarmState state;
                try {
                    state = AlarmState.valueOf(stateParam);
                } catch (IllegalArgumentException e) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return AuthUtil.errorJson("Invalid state value: " + stateParam
                            + ". Valid values: ClearUnacked, ClearAcked, ActiveUnacked, ActiveAcked");
                }
                filter.and(AlarmFilter.STATE, new EnumCondition<>(state));
            }

            AlarmQueryResult results = context.getAlarmManager().queryJournal(journal, filter);

            TagScope scope = TagScope.forSite(site);
            JsonArray alarms = new JsonArray();
            for (AlarmEvent event : results) {
                if (!inScope(scope, event)) {
                    continue;
                }
                alarms.add(serializeEvent(event));
            }

            JsonObject result = new JsonObject();
            result.addProperty("start", startDate.toInstant().toString());
            result.addProperty("end", endDate.toInstant().toString());
            result.add("alarms", alarms);
            result.addProperty("count", alarms.size());
            return result;

        } catch (AuthUtil.BadRequestException e) {
            throw e;
        } catch (Exception e) {
            return AuthUtil.internalError(res, log, "Error querying alarm history", e);
        }
    }

    /**
     * Acknowledge one or more alarms by event UUID.
     *
     * Request body:
     *   { "ids": ["uuid1", "uuid2", ...] }   — array form
     *   { "id":  "uuid1" }                   — single-id shorthand (also accepted)
     *
     * Response: { "acknowledged": n, "requested": m } — n counts requested ids that are acked after the call
     * (already-acked included; unknown ids not counted).
     */
    private static Object acknowledgeAlarms(RequestContext req, HttpServletResponse res,
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
        if (!AuthUtil.canWrite(req, context, site)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("write access required");
        }

        JsonObject parsed = AuthUtil.parseJsonObject(req);
        SignatureUtil.Request sigReq = SignatureUtil.parse(parsed);

        // Collect UUIDs — accept "ids" array or single "id" string
        List<UUID> uuids = new ArrayList<>();
        if (parsed.has("ids")) {
            JsonArray ids;
            try {
                ids = parsed.getAsJsonArray("ids");
            } catch (RuntimeException e) {
                throw new AuthUtil.BadRequestException("invalid ids");
            }
            for (int i = 0; i < ids.size(); i++) {
                String raw = stringValue(ids.get(i), "ids");
                try {
                    uuids.add(UUID.fromString(raw));
                } catch (IllegalArgumentException e) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return AuthUtil.errorJson("Invalid UUID: " + raw);
                }
            }
        } else if (parsed.has("id")) {
            String raw = stringValue(parsed.get("id"), "id");
            try {
                uuids.add(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return AuthUtil.errorJson("Invalid UUID: " + raw);
            }
        } else {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Missing 'ids' field in request body");
        }

        if (uuids.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("No alarm IDs provided");
        }

        try {
            TagScope scope = TagScope.forSite(site);
            if (!scope.isUnrestricted()) {
                // resolve each id to its event source via current status; unknown ids cannot be verified
                java.util.Map<UUID, String> sources = new java.util.HashMap<>();
                for (AlarmEvent event : context.getAlarmManager().queryStatus(new AlarmFilter())) {
                    if (event.getId() != null) {
                        sources.put(event.getId(), event.getSource() != null ? event.getSource().toString() : null);
                    }
                }
                for (UUID id : uuids) {
                    if (!sources.containsKey(id) || !scope.allowsAlarmSource(sources.get(id))) {
                        return TagScope.deny(res, log, site, sources.get(id) != null ? sources.get(id) : id);
                    }
                }
            }
            Object sigDenied = SignatureUtil.gate(req, res, context, site, sigReq);
            if (sigDenied != null) {
                return sigDenied;
            }
            String actor = AuthUtil.getActorIdentity(req, context);
            EventData ackData = new EventData(System.currentTimeMillis());
            applyAckUser(ackData, actor, context);
            context.getAlarmManager().acknowledgeBulk(uuids, ackData);

            // acknowledgeBulk returns only state-changed ids (empty for Cleared alarms): re-read the
            // requested ids and count those now acked; unknown ids are simply not counted
            Set<UUID> requested = new java.util.HashSet<>(uuids);
            int acknowledged = 0;
            for (AlarmEvent event : context.getAlarmManager().queryStatus(new AlarmFilter())) {
                if (event.getId() != null && requested.contains(event.getId()) && event.isAcked()) {
                    acknowledged++;
                }
            }

            // Audit the acknowledgment
            String auditProfile = AuditUtil.getAuditProfileForRequest(req, context);
            AuditUtil.audit(context, auditProfile, "Alarm Acknowledge",
                    uuids.toString(),
                    SignatureUtil.actionValue("acknowledged " + acknowledged + " of " + requested.size(),
                            sigReq.signature, sigReq.reason),
                    actor, req);

            JsonObject result = new JsonObject();
            result.addProperty("acknowledged", acknowledged);
            result.addProperty("requested", requested.size());
            return result;
        } catch (AuthUtil.BadRequestException e) {
            throw e;
        } catch (Exception e) {
            return AuthUtil.internalError(res, log, "Error acknowledging alarms", e);
        }
    }

    /**
     * Records who acknowledged: AckUserName is the actor string; AckUser is a qualified path
     * (UserProfile + User) for users, or just User for "api-key:<site>" / "anonymous" actors.
     */
    private static void applyAckUser(EventData ackData, String actor, GatewayContext context) {
        ackData.set(CommonAlarmProperties.AckUserName, actor);
        QualifiedPath path = new QualifiedPath();
        if (!actor.startsWith("api-key:") && !"anonymous".equals(actor)) {
            String profile = SettingsUtil.get(context).getLoginAuthProfile();
            if (profile != null && !profile.isBlank()) {
                path = path.extend(WellKnownPathTypes.UserProfile, profile);
            }
        }
        ackData.set(CommonAlarmProperties.AckUser, path.extend(WellKnownPathTypes.User, actor));
    }

    // -------------------------------------------------------------------------
    // Input helpers
    // -------------------------------------------------------------------------

    private static QualifiedPath parseAlarmPath(String path) {
        QualifiedPath parsed = QualifiedPath.parseSafe(path);
        if (parsed == null) {
            throw new AuthUtil.BadRequestException("invalid path: " + path);
        }
        return parsed;
    }

    /** True if the event's source (QualifiedPath form) maps into the site's tag scope. */
    private static boolean inScope(TagScope scope, AlarmEvent event) {
        return scope.allowsAlarmSource(event.getSource() != null ? event.getSource().toString() : null);
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new AuthUtil.BadRequestException("invalid " + field);
        }
    }

    private static String stringValue(JsonElement el, String field) {
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            throw new AuthUtil.BadRequestException("invalid " + field);
        }
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /** Serialize a single AlarmEvent to a JSON object. */
    private static JsonObject serializeEvent(AlarmEvent event) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", event.getId() != null ? event.getId().toString() : null);
        obj.addProperty("source", event.getSource() != null ? event.getSource().toString() : null);
        obj.addProperty("displayPath", event.getDisplayPathOrSource());
        obj.addProperty("name", event.getName());
        obj.addProperty("label", event.getLabel());
        obj.addProperty("priority", event.getPriority() != null ? event.getPriority().toString() : null);
        obj.addProperty("state", event.getState() != null ? event.getState().toString() : null);
        obj.addProperty("isAcked", event.isAcked());
        obj.addProperty("isCleared", event.isCleared());
        obj.addProperty("isShelved", event.isShelved());

        // Active timestamp
        EventData activeData = event.getActiveData();
        if (activeData != null && activeData.getTimestamp() > 0) {
            obj.addProperty("activeTime", Instant.ofEpochMilli(activeData.getTimestamp()).toString());
        }

        // Ack timestamp
        EventData ackData = event.getAckData();
        if (ackData != null && ackData.getTimestamp() > 0) {
            obj.addProperty("ackTime", Instant.ofEpochMilli(ackData.getTimestamp()).toString());
        }
        if (ackData != null) {
            String ackUser = ackData.get(CommonAlarmProperties.AckUserName);
            if ((ackUser == null || ackUser.isBlank()) && ackData.get(CommonAlarmProperties.AckUser) != null) {
                ackUser = ackData.get(CommonAlarmProperties.AckUser).getLastPathComponent();
            }
            if (ackUser != null && !ackUser.isBlank()) {
                obj.addProperty("ackUser", ackUser);
            }
        }

        // Clear timestamp
        EventData clearData = event.getClearedData();
        if (clearData != null && clearData.getTimestamp() > 0) {
            obj.addProperty("clearTime", Instant.ofEpochMilli(clearData.getTimestamp()).toString());
        }

        String notes = event.getNotes();
        if (notes != null && !notes.isBlank()) {
            obj.addProperty("notes", notes);
        }

        return obj;
    }

}
