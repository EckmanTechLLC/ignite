package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.ignite.gateway.ScriptExecutor;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * REST route handlers for Ignition script execution.
 *
 * Routes are mounted under the module's route group (alias "ignite"):
 *   POST /script/library  — execute a project library function
 *   POST /script/system   — execute a system.* function
 *   GET  /script/projects — list running project names
 *
 * Auth: X-Ignite-Key header or apiKey query param, or active Ignition session.
 * /script/library and /script/system require write tier.
 *
 * Security notes (ADR-004): write-tier callers can invoke any system.* function; this is an
 * accepted trade-off and the tier is not narrowed here. The execution timeout is response-only:
 * the worker thread is interrupted, not killed. Every execution is audited regardless of outcome.
 *
 * Tag scope (ADR-004 A1): out of scope by nature. The site's TagPathPatterns are NOT applied here;
 * scripts run gateway-wide under the write tier. A site that must not run scripts should set
 * WriteSecurityLevel to a role its users lack.
 */
public final class ScriptRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.ScriptRoutes");

    private static final Pattern FUNCTION_PATH =
            Pattern.compile("^[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)+$");

    private ScriptRoutes() {}

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

    public static void mountRoutes(RouteGroup routes, GatewayContext context, ScriptExecutor executor) {

        // POST /script/library
        // Body: {"project": "MyProject", "path": "demo.helloWorld", "args": [...], "kwargs": {...}}
        routes.newRoute("/script/library")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> executeLibrary(req, res, context, executor)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // POST /script/system
        // Body: {"project": "MyProject", "function": "system.tag.readBlocking", "args": [...], "kwargs": {...}}
        routes.newRoute("/script/system")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> executeSystem(req, res, context, executor)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // GET /script/projects
        // Returns: {"projects": ["ProjectA", "ProjectB"]}
        routes.newRoute("/script/projects")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> listProjects(req, res, context, executor)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /**
     * POST /script/library
     * Required body fields: project, path
     * Optional body fields: args (array), kwargs (object)
     */
    private static Object executeLibrary(RequestContext req, HttpServletResponse res,
                                          GatewayContext context, ScriptExecutor executor) {
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

        // Parse body
        JsonObject body = AuthUtil.parseJsonObject(req);
        SignatureUtil.Request sigReq = SignatureUtil.parse(body);

        // Validate required fields
        String projectName = getStringField(body, "project");
        if (projectName == null) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("project is required");
        }
        String functionPath = getStringField(body, "path");
        if (functionPath == null) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("path is required");
        }
        if (!FUNCTION_PATH.matcher(functionPath).matches()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("invalid function path");
        }
        if (!executor.getProjectNames().contains(projectName)) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("unknown project");
        }

        JsonArray args;
        JsonObject kwargs;
        try {
            args   = body.has("args")   ? body.get("args").getAsJsonArray()   : null;
            kwargs = body.has("kwargs") ? body.get("kwargs").getAsJsonObject() : null;
        } catch (RuntimeException e) {
            throw new AuthUtil.BadRequestException("args must be an array and kwargs an object");
        }

        Object sigDenied = SignatureUtil.gate(req, res, context, site, sigReq);
        if (sigDenied != null) {
            return sigDenied;
        }

        String outcome = "error";
        try {
            JsonObject result = executor.executeLibraryFunction(projectName, functionPath, args, kwargs);
            outcome = "executed";
            return result;
        } catch (TimeoutException e) {
            outcome = "timeout";
            res.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
            return AuthUtil.errorJson("script execution timed out");
        } catch (ScriptExecutor.SaturatedException e) {
            outcome = "rejected";
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return AuthUtil.errorJson("script executor busy");
        } catch (IllegalArgumentException e) {
            log.debug("Rejected script request: " + functionPath, e);
            return AuthUtil.badRequest(res, "invalid function or project");
        } catch (Exception e) {
            return AuthUtil.internalError(res, log, "Script execution failed: " + functionPath, e);
        } finally {
            String actor = AuthUtil.getActorIdentity(req, context);
            String auditProfile = AuditUtil.getAuditProfileForRequest(req, context);
            AuditUtil.audit(context, auditProfile, "Script Library", functionPath,
                    SignatureUtil.actionValue(outcome, sigReq.signature, sigReq.reason), actor, req);
        }
    }

    /**
     * POST /script/system
     * Required body fields: function. Optional: project (defaults to gateway scope)
     * Optional body fields: args (array), kwargs (object)
     */
    private static Object executeSystem(RequestContext req, HttpServletResponse res,
                                         GatewayContext context, ScriptExecutor executor) {
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

        // Parse body
        JsonObject body = AuthUtil.parseJsonObject(req);

        // Validate fields
        String projectName = getStringField(body, "project");
        String functionPath = getStringField(body, "function");
        if (functionPath == null) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("function is required");
        }
        if (!FUNCTION_PATH.matcher(functionPath).matches() || !functionPath.startsWith("system.")) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("invalid function path");
        }
        if (projectName != null && !executor.getProjectNames().contains(projectName)) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("unknown project");
        }

        JsonArray args;
        JsonObject kwargs;
        try {
            args   = body.has("args")   ? body.get("args").getAsJsonArray()   : null;
            kwargs = body.has("kwargs") ? body.get("kwargs").getAsJsonObject() : null;
        } catch (RuntimeException e) {
            throw new AuthUtil.BadRequestException("args must be an array and kwargs an object");
        }

        String outcome = "error";
        try {
            JsonObject result = executor.executeSystemFunction(projectName, functionPath, args, kwargs);
            outcome = "executed";
            return result;
        } catch (TimeoutException e) {
            outcome = "timeout";
            res.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
            return AuthUtil.errorJson("script execution timed out");
        } catch (ScriptExecutor.SaturatedException e) {
            outcome = "rejected";
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return AuthUtil.errorJson("script executor busy");
        } catch (IllegalArgumentException e) {
            log.debug("Rejected script request: " + functionPath, e);
            return AuthUtil.badRequest(res, "invalid function or project");
        } catch (Exception e) {
            return AuthUtil.internalError(res, log, "Script execution failed: " + functionPath, e);
        } finally {
            String actor = AuthUtil.getActorIdentity(req, context);
            String auditProfile = AuditUtil.getAuditProfileForRequest(req, context);
            AuditUtil.audit(context, auditProfile, "Script System", functionPath, outcome, actor, req);
        }
    }

    /**
     * GET /script/projects
     * Returns the list of running project names.
     */
    private static Object listProjects(RequestContext req, HttpServletResponse res,
                                        GatewayContext context, ScriptExecutor executor) {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }

        List<String> names = executor.getProjectNames();
        com.inductiveautomation.ignition.common.gson.JsonArray arr =
                new com.inductiveautomation.ignition.common.gson.JsonArray();
        for (String name : names) {
            arr.add(name);
        }

        JsonObject result = new JsonObject();
        result.add("projects", arr);
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Return a trimmed string field value, or null if absent/blank. */
    private static String getStringField(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        String val;
        try {
            val = obj.get(key).getAsString().trim();
        } catch (RuntimeException e) {
            throw new AuthUtil.BadRequestException("invalid " + key);
        }
        return val.isBlank() ? null : val;
    }
}
