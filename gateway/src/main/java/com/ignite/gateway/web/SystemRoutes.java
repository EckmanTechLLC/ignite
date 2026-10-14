package com.ignite.gateway.web;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonNull;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.licensing.LicenseMode;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.modules.ModuleInfo;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import jakarta.servlet.http.HttpServletResponse;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * REST route handlers for Ignition gateway system information.
 *
 * Routes are mounted under the module's route group (alias "ignite"):
 *   GET /system/status — gateway name, uptime, platform licence/trial state (read tier)
 *   GET /system/info — gateway name, uptime, JVM info, module count, timezone
 *
 * Auth: requires a user identity (session or Bearer) holding the gateway admin
 * security level from the settings record. API keys are never sufficient.
 */
public final class SystemRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.SystemRoutes");

    private SystemRoutes() {}

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

        // GET /system/status
        routes.newRoute("/system/status")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> getSystemStatus(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // GET /system/info
        routes.newRoute("/system/info")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> getSystemInfo(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    // -------------------------------------------------------------------------
    // Handler
    // -------------------------------------------------------------------------

    /**
     * Return gateway status for UIs: name, uptime and the platform licence state. The platform
     * trial gates the historian and alarm journal, so the platform state is the representative one.
     * Read tier: any authenticated caller (session, Bearer or API key) with a resolved site.
     */
    private static Object getSystemStatus(RequestContext req, HttpServletResponse res,
                                          GatewayContext context) {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        if (AuthUtil.requireSite(req, context) == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }

        JsonObject status = new JsonObject();
        try {
            status.addProperty("gatewayName", context.getSystemPropertiesManager().getSystemName());
        } catch (Exception e) {
            log.warn("Could not retrieve gateway name", e);
            status.addProperty("gatewayName", "unknown");
        }
        status.addProperty("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());

        JsonObject license = new JsonObject();
        try {
            LicenseState state = context.getLicenseManager().getPlatformLicenseState();
            LicenseMode mode = state.getLicenseMode();
            boolean expired = state.isTrialExpired();
            license.addProperty("mode", mode != null ? mode.name() : "Unknown");
            license.addProperty("trialExpired", expired);
            Date exp = state.getTrialExpirationDate();
            if (mode == LicenseMode.Trial && exp != null) {
                license.addProperty("trialTimeLeftMs", Math.max(0L, exp.getTime() - System.currentTimeMillis()));
            } else {
                license.add("trialTimeLeftMs", JsonNull.INSTANCE);
            }
        } catch (Exception e) {
            log.warn("Could not retrieve licence state", e);
            license.addProperty("mode", "Unknown");
            license.addProperty("trialExpired", false);
            license.add("trialTimeLeftMs", JsonNull.INSTANCE);
        }
        status.add("license", license);
        return status;
    }

    /**
     * Return gateway metadata: name, uptime, JVM, OS, timezone, active modules.
     */
    private static Object getSystemInfo(RequestContext req, HttpServletResponse res,
                                        GatewayContext context) {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        if (!AuthUtil.canAdminGateway(req, context)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("gateway admin access required");
        }

        JsonObject info = new JsonObject();

        // Gateway name
        try {
            info.addProperty("gatewayName",
                    context.getSystemPropertiesManager().getSystemName());
        } catch (Exception e) {
            log.warn("Could not retrieve gateway name", e);
            info.addProperty("gatewayName", "unknown");
        }

        // JVM uptime
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        info.addProperty("uptimeMs", runtime.getUptime());

        // JVM info
        info.addProperty("jvmVersion", System.getProperty("java.version", "unknown"));
        info.addProperty("jvmVendor",  System.getProperty("java.vendor",  "unknown"));

        // OS info
        info.addProperty("os",   System.getProperty("os.name",    "unknown")
                + " " + System.getProperty("os.version", ""));
        info.addProperty("arch", System.getProperty("os.arch",    "unknown"));

        // Timezone
        info.addProperty("timezone", TimeZone.getDefault().getID());

        // Active modules
        try {
            List<ModuleInfo> activeModules = context.getModuleManager().getActiveModules();
            info.addProperty("moduleCount", activeModules.size());

            JsonArray modules = new JsonArray();
            for (ModuleInfo mod : activeModules) {
                JsonObject m = new JsonObject();
                m.addProperty("id",      mod.getId());
                m.addProperty("name",    mod.getName());
                m.addProperty("version", mod.getVersion() != null
                        ? mod.getVersion().toString() : "unknown");
                modules.add(m);
            }
            info.add("modules", modules);
        } catch (Exception e) {
            log.warn("Could not retrieve module list", e);
            info.addProperty("moduleCount", -1);
        }

        return info;
    }

}
