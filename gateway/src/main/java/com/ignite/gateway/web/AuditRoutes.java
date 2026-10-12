package com.ignite.gateway.web;

import com.ignite.gateway.ScriptExecutor;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import jakarta.servlet.http.HttpServletResponse;

import java.util.concurrent.TimeoutException;

/**
 * REST route handler for querying Ignition audit logs.
 *
 * Route mounted under the module's route group (alias "ignite"):
 *   GET /audit/logs — query audit log entries via system.util.queryAuditLog()
 *
 * Auth: requires an authenticated session or Bearer token holding the gateway admin
 * security level. API keys are never sufficient.
 */
public final class AuditRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.AuditRoutes");

    // Jython script executed via ScriptExecutor.
    // Receives params via _kwargs dict, converts dataset rows to a list of dicts.
    // Date values are converted to epoch millis for JSON-friendly output.
    private static final String AUDIT_QUERY_SCRIPT =
            "from java.util import Date\n" +
            "profile  = _kwargs.get('auditProfile', '')\n" +
            "start    = Date(long(_kwargs['startMillis'])) if 'startMillis' in _kwargs else None\n" +
            "end      = Date(long(_kwargs['endMillis']))   if 'endMillis'   in _kwargs else None\n" +
            "actor_f  = _kwargs.get('actor',  None)\n" +
            "action_f = _kwargs.get('action', None)\n" +
            "target_f = _kwargs.get('target', None)\n" +
            "ds = system.util.queryAuditLog(profile, start, end, actor_f, action_f, target_f, None, None, None)\n" +
            "rows = []\n" +
            "if ds is not None:\n" +
            "    headers = list(ds.getColumnNames())\n" +
            "    for row in range(ds.getRowCount()):\n" +
            "        entry = {}\n" +
            "        for col in headers:\n" +
            "            val = ds.getValueAt(row, col)\n" +
            "            if isinstance(val, Date):\n" +
            "                entry[col] = val.getTime()\n" +
            "            else:\n" +
            "                entry[col] = val\n" +
            "        rows.append(entry)\n" +
            "result = rows\n";

    private AuditRoutes() {}

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

    public static void mountRoutes(RouteGroup routes, GatewayContext context, ScriptExecutor executor) {
        // GET /audit/logs?auditProfile=...&startDate=...&endDate=...&actor=...&action=...&target=...
        routes.newRoute("/audit/logs")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> queryAuditLog(req, res, context, executor)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    // -------------------------------------------------------------------------
    // Handler
    // -------------------------------------------------------------------------

    /**
     * GET /audit/logs
     *
     * Query parameters:
     *   auditProfile  (required) — Ignition audit profile name
     *   startDate     (optional) — epoch millis or ISO 8601 (e.g., 2024-01-01T00:00:00Z)
     *   endDate       (optional) — epoch millis or ISO 8601
     *   actor         (optional) — filter by actor (substring)
     *   action        (optional) — filter by action (substring)
     *   target        (optional) — filter by action target (substring)
     */
    private static Object queryAuditLog(RequestContext req, HttpServletResponse res,
                                         GatewayContext context, ScriptExecutor executor) {
        // Auth check
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }

        // Gateway admin required — audit logs are sensitive
        if (!AuthUtil.canAdminGateway(req, context)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("gateway admin access required");
        }

        // Parse required param
        String auditProfile = req.getParameter("auditProfile");
        if (auditProfile == null || auditProfile.isBlank()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("auditProfile parameter is required");
        }

        // Parse optional params
        String startDate = req.getParameter("startDate");
        String endDate   = req.getParameter("endDate");
        String actor     = req.getParameter("actor");
        String action    = req.getParameter("action");
        String target    = req.getParameter("target");

        // Build kwargs for the Jython script
        JsonObject kwargs = new JsonObject();
        kwargs.addProperty("auditProfile", auditProfile);

        if (startDate != null && !startDate.isBlank()) {
            try {
                kwargs.addProperty("startMillis", parseToEpochMillis(startDate));
            } catch (Exception e) {
                return AuthUtil.badRequest(res, "Invalid startDate");
            }
        }
        if (endDate != null && !endDate.isBlank()) {
            try {
                kwargs.addProperty("endMillis", parseToEpochMillis(endDate));
            } catch (Exception e) {
                return AuthUtil.badRequest(res, "Invalid endDate");
            }
        }
        if (actor  != null && !actor.isBlank())  { kwargs.addProperty("actor",  actor);  }
        if (action != null && !action.isBlank())  { kwargs.addProperty("action", action); }
        if (target != null && !target.isBlank())  { kwargs.addProperty("target", target); }

        try {
            JsonObject result = executor.executeScript(null, AUDIT_QUERY_SCRIPT, kwargs);

            // Audit the query itself
            String actor2 = AuthUtil.getActorIdentity(req, context);
            String profile = AuditUtil.getAuditProfileForRequest(req, context);
            AuditUtil.audit(context, profile, "Audit Log Query", auditProfile, "queried", actor2, req);

            return result;
        } catch (TimeoutException e) {
            res.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
            return AuthUtil.errorJson("audit log query timed out");
        } catch (IllegalArgumentException e) {
            log.debug("Rejected audit log query for profile: " + auditProfile, e);
            return AuthUtil.badRequest(res, "invalid audit log query");
        } catch (Exception e) {
            return AuthUtil.internalError(res, log, "Audit log query failed for profile: " + auditProfile, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parse a date string to epoch milliseconds.
     * Accepts: epoch millis (long), or ISO 8601 (e.g., "2024-01-01T00:00:00Z").
     */
    private static long parseToEpochMillis(String dateStr) {
        try {
            return Long.parseLong(dateStr.trim());
        } catch (NumberFormatException e) {
            // Try ISO 8601
            return java.time.Instant.parse(dateStr.trim()).toEpochMilli();
        }
    }
}
