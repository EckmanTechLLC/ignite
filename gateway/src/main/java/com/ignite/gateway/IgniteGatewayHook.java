package com.ignite.gateway;

import com.ignite.common.IgniteModule;
import com.ignite.gateway.records.IgniteSettingsRecord;
import com.ignite.gateway.records.SiteRecord;
import com.ignite.gateway.sync.GitSyncService;
import com.ignite.gateway.web.AlarmRoutes;
import com.ignite.gateway.web.AuditRoutes;
import com.ignite.gateway.web.AuthRoutes;
import com.ignite.gateway.web.AuthUtil;
import com.ignite.gateway.web.DbRoutes;
import com.ignite.gateway.web.ScriptRoutes;
import com.ignite.gateway.web.SettingsRoutes;
import com.ignite.gateway.web.SessionStore;
import com.ignite.gateway.web.SiteConfigRoutes;
import com.ignite.gateway.web.StaticFileRoutes;
import com.ignite.gateway.web.StaticFileServlet;
import com.ignite.gateway.web.SubscriptionRoutes;
import com.ignite.gateway.web.SystemRoutes;
import com.ignite.gateway.web.TagRoutes;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.nav.ReactComponentInfo;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Gateway Hook — entry point for the Ignite module on the Gateway.
 *
 * Responsible for initializing all gateway-side services:
 * site records, static file serving, REST endpoints, WebSocket/SSE.
 */
public class IgniteGatewayHook extends AbstractGatewayModuleHook {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.IgniteGatewayHook");

    private GatewayContext context;
    private GitSyncService syncService;
    private ScriptExecutor scriptExecutor;
    private SessionStore sessionStore;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        log.info("Ignite module setup on Gateway.");

        // Register i18n bundle
        BundleUtil.get().addBundle("Ignite", getClass(), "Ignite");

        // Register SiteRecord schema in Ignition's internal SQLite DB
        try {
            context.getSchemaUpdater().updatePersistentRecords(SiteRecord.META);
            log.info("SiteRecord schema registered.");
        } catch (SQLException e) {
            log.error("Failed to register SiteRecord schema.", e);
        }

        // Register IgniteSettingsRecord schema and ensure the singleton row exists
        try {
            context.getSchemaUpdater().updatePersistentRecords(IgniteSettingsRecord.META);
            log.info("IgniteSettingsRecord schema registered.");
        } catch (SQLException e) {
            log.error("Failed to register IgniteSettingsRecord schema.", e);
        }
        SettingsUtil.ensureExists(context);

        // Instantiate the git sync service (started in startup())
        syncService = new GitSyncService(context);

        // Instantiate the script executor
        scriptExecutor = new ScriptExecutor(context);

        // Create session store for Bearer token auth and wire into AuthUtil
        sessionStore = new SessionStore();
        AuthUtil.setSessionStore(sessionStore);

        // Static files served via RouteGroup catch-all in mountRouteHandlers().

        // Register the SystemJS module so the gateway config UI can load the React component.
        // The JS file is served from gateway/src/main/resources/mounted/ignite-sites.js
        // at /res/ignite/ignite-sites.js (mounted resource alias "ignite").
        try {
            context.getWebResourceManager()
                   .getSystemJsModuleRegistry()
                   .add("ignite-sites", "/res/ignite/ignite-sites.js");
            log.info("Registered SystemJS module: ignite-sites -> /res/ignite/ignite-sites.js");
        } catch (Exception e) {
            log.warn("Could not register SystemJS module (non-fatal).", e);
        }

        // Register nav entry: Connections > Ignite > Sites
        registerNavEntry(context);
    }

    @Override
    public void startup(LicenseState activationState) {
        log.info("Ignite module starting up on Gateway.");
        syncService.start();
        sessionStore.start(context.getScheduledExecutorService(), AuthRoutes::cleanupTick);
    }

    @Override
    public void shutdown() {
        log.info("Ignite module shutting down on Gateway.");
        if (syncService != null) {
            syncService.stop();
        }
        if (sessionStore != null) {
            sessionStore.stop();
        }
        AuthUtil.setSessionStore(null);
        if (scriptExecutor != null) {
            scriptExecutor.shutdown();
        }
        // No servlet cleanup needed — static files served via RouteGroup.
        BundleUtil.get().removeBundle("Ignite");
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        log.info("Mounting Ignite REST routes.");
        AuthRoutes.mountRoutes(routes, context, sessionStore);
        SiteConfigRoutes.mountRoutes(routes, context, syncService);
        SettingsRoutes.mountRoutes(routes, context);
        TagRoutes.mountRoutes(routes, context);
        DbRoutes.mountRoutes(routes, context);
        AlarmRoutes.mountRoutes(routes, context);
        SystemRoutes.mountRoutes(routes, context);
        SubscriptionRoutes.mountRoutes(routes, context);
        // Env endpoint only (/sites/:name/env). Static files are served via
        // StaticFileServlet registered at /ignite-sites/* (see setup()).
        StaticFileRoutes.mountRoutes(routes, context);
        ScriptRoutes.mountRoutes(routes, context, scriptExecutor);
        AuditRoutes.mountRoutes(routes, context, scriptExecutor);
    }

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("ignite");
    }

    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Adds "Ignite > Sites" under the Connections section of the Gateway config UI.
     *
     * The React component ("ignite-sites") is served from the gateway's mounted
     * resources at /res/ignite/ignite-sites.js and registered in the SystemJS
     * module registry so Ignition's config UI can load it dynamically.
     */
    private void registerNavEntry(GatewayContext context) {
        try {
            context.getWebResourceManager()
                    .getNavigationModel()
                    .getConnections()
                    .addCategory("ignite", cb ->
                            cb.label(BundleUtil.get().getString("Ignite.nav.header"))
                              .position(700)
                              .addPage("sites", pb ->
                                      pb.title(BundleUtil.get().getString("Ignite.nav.sites.title"))
                                        .mount("ignite/sites", new ReactComponentInfo(
                                                "ignite-sites",
                                                new SystemJsModule("ignite-sites", "/res/ignite/ignite-sites.js")))
                                        .build()
                              )
                              .build()
                    );
            log.info("Registered nav entry: Connections > Ignite > Sites.");
        } catch (Exception e) {
            log.warn("Could not register nav entry (non-fatal).", e);
        }
    }
}
