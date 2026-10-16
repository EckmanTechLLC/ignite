package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.ignite.gateway.sync.GitSyncService;
import com.ignite.gateway.sync.SyncStatus;
import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import simpleorm.dataset.SQuery;
import simpleorm.utils.SException;

import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * REST route handlers for site configuration CRUD.
 *
 * Routes are mounted under the module's route group (alias "ignite"):
 *   GET    /sites          — list all sites
 *   POST   /sites          — create a site
 *   GET    /sites/:id      — get one site
 *   PUT    /sites/:id      — update a site
 *   DELETE /sites/:id      — delete a site
 *   POST   /sites/:id/test — test git connectivity
 */
public final class SiteConfigRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.SiteConfigRoutes");

    private static final Gson GSON = new Gson();

    private SiteConfigRoutes() {}

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

        // GET /sites — list all configured sites (includes sync status)
        routes.newRoute("/sites")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> listSites(req, res, context, syncService)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // POST /sites — create a new site
        routes.newRoute("/sites")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> createSite(req, res, context, syncService)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // GET /sites/:id — retrieve one site by id
        routes.newRoute("/sites/:id")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> getSite(req, res, context, syncService)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // PUT /sites/:id — update an existing site
        routes.newRoute("/sites/:id")
                .method(HttpMethod.PUT)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> updateSite(req, res, context, syncService)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // DELETE /sites/:id — remove a site
        routes.newRoute("/sites/:id")
                .method(HttpMethod.DELETE)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> deleteSite(req, res, context, syncService)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // GET /sites/:id/status — current sync status for a site
        routes.newRoute("/sites/:id/status")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> getSiteStatus(req, res, context, syncService)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // POST /sites/:id/sync — trigger an immediate sync
        routes.newRoute("/sites/:id/sync")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> triggerSync(req, res, context, syncService)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // POST /sites/:id/test — verify git repo is reachable
        routes.newRoute("/sites/:id/test")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> testSiteConnection(req, res, context, syncService)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private static Object listSites(RequestContext req, HttpServletResponse res,
                                    GatewayContext context, GitSyncService syncService) {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        if (!AuthUtil.canAdminGateway(req, context)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("gateway admin access required");
        }

        JsonArray result = new JsonArray();
        try {
            PersistenceInterface db = context.getLocalPersistenceInterface();
            List<SiteRecord> sites = db.query(new SQuery<>(SiteRecord.META));
            for (SiteRecord site : sites) {
                result.add(toJson(site, syncService));
            }
        } catch (Exception e) {
            log.error("Failed to list sites", e);
        }
        return result;
    }

    private static Object createSite(RequestContext req, HttpServletResponse res,
                                     GatewayContext context,
                                     GitSyncService syncService) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        if (!AuthUtil.canAdminGateway(req, context)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("gateway admin access required");
        }

        JsonObject data = AuthUtil.parseJsonObject(req);

        PersistenceInterface db = context.getLocalPersistenceInterface();
        SiteRecord site = db.createNew(SiteRecord.META);

        applyJson(data, site);
        validateSite(site);

        if (hasDuplicate(db, site)) {
            res.setStatus(HttpServletResponse.SC_CONFLICT);
            return AuthUtil.errorJson("site name or mount path already exists");
        }

        Object failure = saveSite(db, site, res);
        if (failure != null) {
            return failure;
        }
        db.notifyRecordAdded(site);

        // Start polling for the new site if it is enabled (idempotent: the record listener
        // fired by notifyRecordAdded may already have scheduled it)
        if (syncService != null) {
            syncService.reschedule(site);
        }

        // Audit the site creation
        String createActor = AuthUtil.getActorIdentity(req, context);
        AuditUtil.audit(context, AuditUtil.resolveProfile(context, site), "Site Create",
                site.getName(), "created", createActor, req);

        log.info("Created site: " + site.getName());
        res.setStatus(HttpServletResponse.SC_CREATED);
        return toJson(site, syncService);
    }

    private static Object getSite(RequestContext req, HttpServletResponse res,
                                  GatewayContext context,
                                  GitSyncService syncService) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        long id = parseLongId(req, res);
        if (id < 0) return null;

        SiteRecord site = context.getLocalPersistenceInterface().find(SiteRecord.META, id);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return AuthUtil.errorJson("Site not found: " + id);
        }
        if (!AuthUtil.canAdmin(req, context, site)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("admin access required");
        }
        return toJson(site, syncService);
    }

    private static Object updateSite(RequestContext req, HttpServletResponse res,
                                     GatewayContext context,
                                     GitSyncService syncService) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }

        long id = parseLongId(req, res);
        if (id < 0) return null;

        PersistenceInterface db = context.getLocalPersistenceInterface();
        SiteRecord site = db.find(SiteRecord.META, id);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return AuthUtil.errorJson("Site not found: " + id);
        }
        if (!AuthUtil.canAdmin(req, context, site)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("admin access required");
        }

        JsonObject data = AuthUtil.parseJsonObject(req);
        List<String> changed = applyJson(data, site);
        validateSite(site);

        if (hasDuplicate(db, site)) {
            res.setStatus(HttpServletResponse.SC_CONFLICT);
            return AuthUtil.errorJson("site name or mount path already exists");
        }

        Object failure = saveSite(db, site, res);
        if (failure != null) {
            return failure;
        }
        db.notifyRecordUpdated(site);

        // Re-schedule or cancel based on new enabled state
        if (syncService != null) {
            syncService.reschedule(site);
        }

        // Audit the site update
        String updateActor = AuthUtil.getActorIdentity(req, context);
        AuditUtil.audit(context, AuditUtil.resolveProfile(context, site), "Site Update",
                site.getName(), String.join(",", changed), updateActor, req);

        log.info("Updated site: " + site.getName());
        return toJson(site, syncService);
    }

    private static Object deleteSite(RequestContext req, HttpServletResponse res,
                                     GatewayContext context,
                                     GitSyncService syncService) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }

        long id = parseLongId(req, res);
        if (id < 0) return null;

        PersistenceInterface db = context.getLocalPersistenceInterface();
        SiteRecord site = db.find(SiteRecord.META, id);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return AuthUtil.errorJson("Site not found: " + id);
        }
        if (!AuthUtil.canAdmin(req, context, site)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("admin access required");
        }

        String name = site.getName();
        String deleteAuditProfile = AuditUtil.resolveProfile(context, site);

        site.deleteRecord();
        db.save(site);

        // Stop polling, drop the status entry and remove the synced directory
        if (syncService != null) {
            syncService.removeSite(id, name);
        }

        // Audit the site deletion (capture before deleteRecord loses state)
        String deleteActor = AuthUtil.getActorIdentity(req, context);
        AuditUtil.audit(context, deleteAuditProfile, "Site Delete",
                name, "deleted", deleteActor, req);

        log.info("Deleted site: " + name);
        JsonObject result = new JsonObject();
        result.addProperty("deleted", true);
        result.addProperty("id", id);
        return result;
    }

    private static Object getSiteStatus(RequestContext req, HttpServletResponse res,
                                        GatewayContext context,
                                        GitSyncService syncService) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        long id = parseLongId(req, res);
        if (id < 0) return null;

        SiteRecord site = context.getLocalPersistenceInterface().find(SiteRecord.META, id);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return AuthUtil.errorJson("Site not found: " + id);
        }
        if (!AuthUtil.canAdmin(req, context, site)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("admin access required");
        }

        if (syncService == null) {
            JsonObject obj = new JsonObject();
            obj.addProperty("state", "PENDING");
            obj.addProperty("commitHash", (String) null);
            obj.addProperty("lastSyncTime", (String) null);
            obj.addProperty("error", "Sync service not yet initialized.");
            return obj;
        }

        return syncService.getStatus(id).toJson();
    }

    private static Object triggerSync(RequestContext req, HttpServletResponse res,
                                      GatewayContext context,
                                      GitSyncService syncService) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }

        long id = parseLongId(req, res);
        if (id < 0) return null;

        SiteRecord site = context.getLocalPersistenceInterface().find(SiteRecord.META, id);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return AuthUtil.errorJson("Site not found: " + id);
        }
        if (!AuthUtil.canAdmin(req, context, site)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("admin access required");
        }

        String notQueued = syncService != null ? syncService.triggerSync(id) : "sync service unavailable";

        AuditUtil.audit(context, AuditUtil.resolveProfile(context, site), "Site Sync",
                site.getName(), "triggered", AuthUtil.getActorIdentity(req, context), req);

        JsonObject result = new JsonObject();
        result.addProperty("queued", notQueued == null);
        if (notQueued != null) {
            result.addProperty("reason", notQueued);
        }
        result.addProperty("id", id);
        return result;
    }

    private static Object testSiteConnection(RequestContext req, HttpServletResponse res,
                                             GatewayContext context,
                                             GitSyncService syncService) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }

        long id = parseLongId(req, res);
        if (id < 0) return null;

        SiteRecord site = context.getLocalPersistenceInterface().find(SiteRecord.META, id);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return AuthUtil.errorJson("Site not found: " + id);
        }
        if (!AuthUtil.canAdmin(req, context, site)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("admin access required");
        }

        String repoUrl = site.getRepoUrl();
        if (repoUrl == null || repoUrl.isBlank()) {
            JsonObject result = new JsonObject();
            result.addProperty("success", false);
            result.addProperty("message", "Repository URL is not configured.");
            return result;
        }

        if (syncService == null) {
            JsonObject result = new JsonObject();
            result.addProperty("success", false);
            result.addProperty("message", "Sync service not yet initialized.");
            return result;
        }

        String error = syncService.testConnectivity(repoUrl, site.getPat(), site.getBranch());
        if (error != null) {
            log.warn("Git connectivity test failed for site '" + site.getName() + "': " + error);
        }
        JsonObject result = new JsonObject();
        AuditUtil.audit(context, AuditUtil.resolveProfile(context, site), "Site Test",
                site.getName(), error == null ? "ok" : "failed",
                AuthUtil.getActorIdentity(req, context), req);
        result.addProperty("success", error == null);
        result.addProperty("message", error == null ? "Repository is reachable." : "Repository is not reachable.");
        result.addProperty("repoUrl", repoUrl);
        return result;
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    /** Serialize a SiteRecord to JSON — omits encoded fields from output. */
    private static JsonObject toJson(SiteRecord site, GitSyncService syncService) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", site.getId());
        obj.addProperty("name", site.getName());
        obj.addProperty("mountPath", site.getMountPath());
        obj.addProperty("repoUrl", site.getRepoUrl());
        obj.addProperty("branch", site.getBranch());
        obj.addProperty("patConfigured", site.getPat() != null && !site.getPat().isBlank());
        obj.addProperty("pollIntervalMinutes", site.getPollIntervalMinutes());
        obj.addProperty("envVars", site.getEnvVars());
        obj.addProperty("corsOrigins", site.getCorsOrigins());
        obj.addProperty("apiKeyConfigured", site.getApiKey() != null && !site.getApiKey().isBlank());
        obj.addProperty("servePath", site.getServePath());
        obj.addProperty("enabled", site.isEnabled());
        obj.addProperty("writeSecurityLevel", site.getWriteSecurityLevel());
        obj.addProperty("adminSecurityLevel", site.getAdminSecurityLevel());
        obj.addProperty("auditProfileName", site.getAuditProfileName());
        obj.addProperty("tagPathPatterns", site.getTagPathPatterns());
        obj.addProperty("requireSignature", site.isRequireSignature());
        if (syncService != null && site.getId() != null) {
            obj.add("sync", syncService.getStatus(site.getId()).toJson());
        }
        return obj;
    }

    /**
     * Apply JSON fields to a SiteRecord — ignores unknown keys. Returns applied key names.
     * Wrongly typed values (e.g. an array for a string field) become a 400.
     */
    private static List<String> applyJson(JsonObject data, SiteRecord site) {
        try {
            return applyJsonFields(data, site);
        } catch (AuthUtil.BadRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AuthUtil.BadRequestException("invalid site field value");
        }
    }

    private static List<String> applyJsonFields(JsonObject data, SiteRecord site) {
        List<String> changed = new ArrayList<>();
        if (data.has("name")) { site.setName(data.get("name").getAsString()); changed.add("name"); }
        if (data.has("mountPath")) { site.setMountPath(data.get("mountPath").getAsString()); changed.add("mountPath"); }
        if (data.has("repoUrl")) { site.setRepoUrl(data.get("repoUrl").getAsString()); changed.add("repoUrl"); }
        if (data.has("branch")) { site.setBranch(data.get("branch").getAsString()); changed.add("branch"); }
        if (data.has("pat")) { site.setPat(data.get("pat").getAsString()); changed.add("pat"); }
        if (data.has("pollIntervalMinutes")) {
            site.setPollIntervalMinutes(data.get("pollIntervalMinutes").getAsInt());
            changed.add("pollIntervalMinutes");
        }
        if (data.has("envVars")) { site.setEnvVars(data.get("envVars").getAsString()); changed.add("envVars"); }
        if (data.has("corsOrigins")) { site.setCorsOrigins(data.get("corsOrigins").getAsString()); changed.add("corsOrigins"); }
        if (data.has("apiKey")) { site.setApiKey(data.get("apiKey").getAsString()); changed.add("apiKey"); }
        if (data.has("servePath")) {
            String sp = data.get("servePath").getAsString();
            if (sp.indexOf('\\') >= 0 || sp.indexOf('\0') >= 0
                    || java.util.Arrays.asList(sp.split("/")).contains("..")) {
                throw new AuthUtil.BadRequestException("servePath may not contain '..' or '\\'");
            }
            site.setServePath(sp);
            changed.add("servePath");
        }
        if (data.has("enabled")) { site.setEnabled(data.get("enabled").getAsBoolean()); changed.add("enabled"); }
        if (data.has("requireSignature")) { site.setRequireSignature(data.get("requireSignature").getAsBoolean()); changed.add("requireSignature"); }
        if (data.has("writeSecurityLevel")) { site.setWriteSecurityLevel(data.get("writeSecurityLevel").getAsString()); changed.add("writeSecurityLevel"); }
        if (data.has("adminSecurityLevel")) { site.setAdminSecurityLevel(data.get("adminSecurityLevel").getAsString()); changed.add("adminSecurityLevel"); }
        if (data.has("auditProfileName")) { site.setAuditProfileName(data.get("auditProfileName").getAsString()); changed.add("auditProfileName"); }
        if (data.has("tagPathPatterns")) {
            String patterns = data.get("tagPathPatterns").getAsString();
            String bad = TagScope.firstInvalid(patterns);
            if (bad != null) {
                throw new AuthUtil.BadRequestException("invalid tag path pattern: " + bad);
            }
            site.setTagPathPatterns(patterns);
            changed.add("tagPathPatterns");
        }
        return changed;
    }

    /** name and mountPath must be non-blank; mountPath is a single path segment. */
    private static void validateSite(SiteRecord site) {
        String name = site.getName();
        if (name == null || name.isBlank()) {
            throw new AuthUtil.BadRequestException("name is required");
        }
        String mountPath = site.getMountPath();
        if (mountPath == null || mountPath.isBlank()) {
            throw new AuthUtil.BadRequestException("mountPath is required");
        }
        if (mountPath.indexOf('/') >= 0) {
            throw new AuthUtil.BadRequestException("mountPath may not contain '/'");
        }
    }

    /**
     * Saves the record. Returns null on success; on a record constraint failure sets 409
     * (uniqueness) or 400 and returns the error body.
     */
    private static Object saveSite(PersistenceInterface db, SiteRecord site, HttpServletResponse res) {
        try {
            db.save(site);
            return null;
        } catch (SException e) {
            log.warn("Site save rejected", e);
            if (isUniqueViolation(e)) {
                res.setStatus(HttpServletResponse.SC_CONFLICT);
                return AuthUtil.errorJson("site name or mount path already exists");
            }
            return AuthUtil.badRequest(res, "invalid site configuration");
        }
    }

    /** True if another site (excluding this record's own id) has the same name (case-insensitive) or mount path. */
    private static boolean hasDuplicate(PersistenceInterface db, SiteRecord site) {
        List<SiteRecord> all = db.query(new SQuery<>(SiteRecord.META));
        for (SiteRecord other : all) {
            if (site.getId() != null && site.getId().equals(other.getId())) {
                continue;
            }
            if (other.getName() != null && other.getName().equalsIgnoreCase(site.getName())) {
                return true;
            }
            if (other.getMountPath() != null && other.getMountPath().equals(site.getMountPath())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUniqueViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null) {
                String lower = m.toLowerCase();
                if (lower.contains("unique") || lower.contains("duplicate")) {
                    return true;
                }
            }
            if (c.getCause() == c) break;
        }
        return false;
    }

    private static long parseLongId(RequestContext req, HttpServletResponse res) {
        try {
            return Long.parseLong(req.getParameter("id"));
        } catch (NumberFormatException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return -1L;
        }
    }

}
