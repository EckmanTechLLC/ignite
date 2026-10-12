package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.ignite.gateway.sync.GitSyncService;
import com.ignite.gateway.sync.SyncStatus;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import simpleorm.dataset.SQuery;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * Read route for the gateway Home "Ignite > Launch" page.
 *
 *   GET /launch — enabled sites with sync state (no keys, repo URLs, PATs or paths)
 *
 * Requires an Ignition gateway web session with ACCESS permission; API keys and Bearer
 * tokens are not accepted.
 */
public final class LaunchRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.LaunchRoutes");

    private LaunchRoutes() {}

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

    public static void mountRoutes(RouteGroup routes, GatewayContext context,
                                   GitSyncService syncService) {
        routes.newRoute("/launch")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> listLaunch(req, res, context, syncService)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    private static boolean hasSessionAccess(RequestContext req) {
        try {
            return WebUiSession.find(req).isPresent()
                    && WebUiSession.SESSION_ACCESS.canAccess(req) == RouteAccess.GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object listLaunch(RequestContext req, HttpServletResponse res,
                                     GatewayContext context, GitSyncService syncService) {
        if (!hasSessionAccess(req)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }

        JsonArray sites = new JsonArray();
        List<SiteRecord> records = context.getLocalPersistenceInterface().query(new SQuery<>(SiteRecord.META));
        for (SiteRecord site : records) {
            if (!site.isEnabled()) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("name", site.getName());
            obj.addProperty("mountPath", site.getMountPath());
            obj.addProperty("url", "/data/ignite/s/" + site.getMountPath() + "/");
            obj.addProperty("enabled", true);
            String state = "PENDING";
            String lastSyncTime = null;
            String commit = null;
            if (syncService != null && site.getId() != null) {
                SyncStatus st = syncService.getStatus(site.getId());
                state = st.getState().name();
                if (st.getLastSyncTime() != null) {
                    lastSyncTime = st.getLastSyncTime().toString();
                }
                commit = st.getCommitHash();
            }
            obj.addProperty("syncState", state);
            obj.addProperty("lastSyncTime", lastSyncTime);
            obj.addProperty("commit", commit);
            sites.add(obj);
        }
        JsonObject result = new JsonObject();
        result.add("sites", sites);
        return result;
    }
}
