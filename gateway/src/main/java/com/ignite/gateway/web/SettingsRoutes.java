package com.ignite.gateway.web;

import com.ignite.gateway.SettingsUtil;
import com.ignite.gateway.records.IgniteSettingsRecord;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParseException;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import jakarta.servlet.http.HttpServletResponse;

/**
 * REST route handlers for the gateway-wide settings record.
 *
 *   GET /settings — read settings
 *   PUT /settings — update any subset of settings
 *
 * Requires an Ignition gateway web session; API keys and Bearer tokens are not accepted.
 */
public final class SettingsRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.SettingsRoutes");

    private SettingsRoutes() {}

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

    public static void mountRoutes(RouteGroup routes, GatewayContext context) {

        routes.newRoute("/settings")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> getSettings(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        routes.newRoute("/settings")
                .method(HttpMethod.PUT)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> updateSettings(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    /**
     * Returns an error body (and sets 401/403) if the caller is not a gateway admin, else null.
     */
    private static Object checkGatewayAdmin(RequestContext req, HttpServletResponse res,
                                             GatewayContext context) {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        if (!AuthUtil.canAdminGateway(req, context)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("gateway admin access required");
        }
        return null;
    }

    private static Object getSettings(RequestContext req, HttpServletResponse res,
                                      GatewayContext context) {
        Object denied = checkGatewayAdmin(req, res, context);
        if (denied != null) {
            return denied;
        }
        return toJson(SettingsUtil.get(context));
    }

    private static Object updateSettings(RequestContext req, HttpServletResponse res,
                                         GatewayContext context) throws Exception {
        Object denied = checkGatewayAdmin(req, res, context);
        if (denied != null) {
            return denied;
        }

        JsonObject data;
        try {
            data = JsonParser.parseString(req.readBody()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Invalid JSON body");
        }

        Integer maxFailures = null;
        Integer lockoutSeconds = null;
        try {
            if (data.has("loginMaxFailures")) {
                maxFailures = data.get("loginMaxFailures").getAsInt();
                if (maxFailures < 1) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return AuthUtil.errorJson("loginMaxFailures must be >= 1");
                }
            }
            if (data.has("loginLockoutSeconds")) {
                lockoutSeconds = data.get("loginLockoutSeconds").getAsInt();
                if (lockoutSeconds < 0) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return AuthUtil.errorJson("loginLockoutSeconds must be >= 0");
                }
            }
        } catch (RuntimeException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Numeric settings must be integers");
        }

        IgniteSettingsRecord settings = SettingsUtil.get(context);
        if (data.has("adminSecurityLevel")) {
            settings.setAdminSecurityLevel(str(data.get("adminSecurityLevel")));
        }
        if (data.has("defaultAuditProfile")) {
            settings.setDefaultAuditProfile(str(data.get("defaultAuditProfile")));
        }
        if (data.has("loginAuthProfile")) {
            settings.setLoginAuthProfile(str(data.get("loginAuthProfile")));
        }
        if (maxFailures != null) {
            settings.setLoginMaxFailures(maxFailures);
        }
        if (lockoutSeconds != null) {
            settings.setLoginLockoutSeconds(lockoutSeconds);
        }

        context.getLocalPersistenceInterface().save(settings);
        context.getLocalPersistenceInterface().notifyRecordUpdated(settings);
        return toJson(settings);
    }

    private static String str(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "";
        }
        try {
            return el.getAsString();
        } catch (RuntimeException e) {
            throw new AuthUtil.BadRequestException("Settings values must be strings");
        }
    }

    private static JsonObject toJson(IgniteSettingsRecord s) {
        JsonObject obj = new JsonObject();
        obj.addProperty("adminSecurityLevel", s.getAdminSecurityLevel());
        obj.addProperty("defaultAuditProfile", s.getDefaultAuditProfile());
        obj.addProperty("loginAuthProfile", s.getLoginAuthProfile());
        obj.addProperty("loginMaxFailures", s.getLoginMaxFailures());
        obj.addProperty("loginLockoutSeconds", s.getLoginLockoutSeconds());
        return obj;
    }
}
