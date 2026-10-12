package com.ignite.gateway.web;

import com.ignite.gateway.SettingsUtil;
import com.ignite.gateway.records.IgniteSettingsRecord;
import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.user.AuthenticatedUser;
import com.inductiveautomation.ignition.common.user.BasicAuthChallenge;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.user.PasswordExpiredException;
import com.inductiveautomation.ignition.gateway.user.UserSourceProfile;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * REST route handlers for Ignite auth/session endpoints.
 *
 * Routes are mounted under the module's route group (alias "ignite"):
 *
 *   GET /auth/session[?site=&lt;name&gt;]
 *       — Returns the current user's identity, security levels, and permission flags.
 *
 *       Site context: resolved from the API key, else the X-Ignite-Site header or
 *       site query param. With no resolved site, canWrite/canAdmin are false.
 *
 *       Response (authenticated):
 *         {
 *           "authenticated": true,
 *           "user": "admin",
 *           "authMethod": "session",   // or "apiKey"
 *           "securityLevels": ["Administrator", "Operator"],
 *           "permissions": { "canWrite": true, "canAdmin": true, "canAdminGateway": false },
 *           "site": "plant1"
 *         }
 *
 *       Response (unauthenticated):
 *         {
 *           "authenticated": false,
 *           "user": null,
 *           "authMethod": null,
 *           "securityLevels": [],
 *           "permissions": { "canWrite": false, "canAdmin": false, "canAdminGateway": false },
 *           "site": null
 *         }
 */
public final class AuthRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.AuthRoutes");

    private static final LoginThrottle loginThrottle = new LoginThrottle();

    private AuthRoutes() {}

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

    /** Called from the SessionStore cleanup tick. */
    public static void cleanupTick() {
        loginThrottle.cleanup();
    }

    // -------------------------------------------------------------------------
    // Route registration
    // -------------------------------------------------------------------------

    public static void mountRoutes(RouteGroup routes, GatewayContext context,
                                    SessionStore sessionStore) {
        routes.newRoute("/auth/session")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> getSession(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // POST /auth/login
        // Body: {"username": "...", "password": "..."}  (authProfile, if sent, is ignored)
        routes.newRoute("/auth/login")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> doLogin(req, res, context, sessionStore)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // POST /auth/logout
        // Requires Authorization: Bearer <token>
        routes.newRoute("/auth/logout")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> doLogout(req, res, context, sessionStore)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /**
     * GET /auth/session[?site=name]
     *
     * Returns identity and permission info for the current request.
     * Never returns 401 — unauthenticated requests get { "authenticated": false }.
     */
    private static Object getSession(RequestContext req, HttpServletResponse res, GatewayContext context) {
        boolean isAuthenticated = AuthUtil.isAuthorized(req, context);

        // Determine auth method
        String authMethod = null;
        String user = null;
        if (isAuthenticated) {
            String apiKey = AuthUtil.extractApiKey(req);
            String actor  = AuthUtil.webSessionUser(req).orElse(null);
            String bearerToken = AuthUtil.extractBearerToken(req);
            if (apiKey != null && !apiKey.isBlank()) {
                authMethod = "apiKey";
                // For API key auth, actor may be empty; use the key-based identity
                user = AuthUtil.getActorIdentity(req, context);
            } else if (bearerToken != null) {
                authMethod = "bearer";
                user = AuthUtil.getActorIdentity(req, context);
            } else if (actor != null && !actor.isBlank()) {
                authMethod = "session";
                user = actor;
            }
        }

        // Collect security levels (only meaningful for session auth)
        List<String> levels = AuthUtil.getUserSecurityLevels(req, context);

        // Evaluate permissions against the site resolved from the API key or X-Ignite-Site / site param
        SiteRecord site = AuthUtil.getSiteForRequest(req, context);
        boolean canWrite = AuthUtil.canWrite(req, context, site);
        boolean canAdmin = AuthUtil.canAdmin(req, context, site);
        boolean canAdminGateway = AuthUtil.canAdminGateway(req, context);

        // Build response
        JsonObject permissions = new JsonObject();
        permissions.addProperty("canWrite", canWrite);
        permissions.addProperty("canAdmin", canAdmin);
        permissions.addProperty("canAdminGateway", canAdminGateway);

        JsonArray securityLevels = new JsonArray();
        for (String level : levels) {
            securityLevels.add(level);
        }

        JsonObject result = new JsonObject();
        result.addProperty("authenticated", isAuthenticated);
        if (user != null) {
            result.addProperty("user", user);
        } else {
            result.add("user", null);
        }
        if (authMethod != null) {
            result.addProperty("authMethod", authMethod);
        } else {
            result.add("authMethod", null);
        }
        result.add("securityLevels", securityLevels);
        result.add("permissions", permissions);
        if (site != null) {
            result.addProperty("site", site.getName());
        } else {
            result.add("site", null);
        }

        return result;
    }

    /**
     * POST /auth/login
     *
     * Authenticates natively against the user source named in the settings record
     * (login auth profile). Returns a Bearer token on success, a generic 401 on any
     * failure, 429 when the (username, IP) pair is locked out.
     *
     * Body: {"username": "...", "password": "..."}  (authProfile, if sent, is ignored)
     * Response: {"token": "...", "user": "...", "roles": [...], "expiresIn": 28800}
     */
    private static Object doLogin(RequestContext req, HttpServletResponse res,
                                   GatewayContext context, SessionStore sessionStore) {
        // Parse body
        JsonObject body = AuthUtil.parseJsonObject(req);

        // Validate required fields
        String username = getStringField(body, "username");
        if (username == null) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("username is required");
        }
        String password = getStringField(body, "password");
        if (password == null) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("password is required");
        }

        // Never log the password
        log.debug("Login attempt for user: " + username);

        IgniteSettingsRecord settings = SettingsUtil.get(context);
        String auditProfile = settings.getDefaultAuditProfile();

        // Throttle check
        String key = LoginThrottle.key(username, req.getRequest().getRemoteAddr());
        int lockoutSeconds = settings.getLoginLockoutSeconds();
        if (loginThrottle.isLocked(key, settings.getLoginMaxFailures(), lockoutSeconds)) {
            AuditUtil.audit(context, auditProfile, "Login", username, "locked", username, req);
            res.setHeader("Retry-After", String.valueOf(lockoutSeconds));
            res.setStatus(429);
            return AuthUtil.errorJson("Too many failed attempts; try again later");
        }

        // Authenticate against the configured user source
        String profileName = settings.getLoginAuthProfile();
        UserSourceProfile profile = context.getUserSourceManager().getProfile(profileName);
        if (profile == null) {
            log.error("Login auth profile not found: " + profileName);
            return loginFailed(req, res, context, auditProfile, key, username);
        }

        AuthenticatedUser user = null;
        try {
            user = profile.authenticate(new BasicAuthChallenge(username, password).toSimpleAuthChallenge());
        } catch (PasswordExpiredException e) {
            log.info("Login rejected, password expired for user: " + username);
        } catch (Exception e) {
            log.debug("Authentication failed for user '" + username + "': " + e.getMessage());
        }
        if (user == null) {
            return loginFailed(req, res, context, auditProfile, key, username);
        }

        // Success
        loginThrottle.clear(key);
        List<String> roles = user.getRoles() != null ? user.getRoles() : List.of();
        String token = sessionStore.createSession(user.getUsername(), roles);
        log.info("Login successful for user: " + user.getUsername() + " (" + roles.size() + " roles)");
        AuditUtil.audit(context, auditProfile, "Login", username, "success", username, req);

        JsonArray rolesArr = new JsonArray();
        for (String role : roles) {
            rolesArr.add(role);
        }

        JsonObject result = new JsonObject();
        result.addProperty("token", token);
        result.addProperty("user", user.getUsername());
        result.add("roles", rolesArr);
        result.addProperty("expiresIn", 28800);
        return result;
    }

    /** Records a failure, audits it, and returns the generic 401. */
    private static Object loginFailed(RequestContext req, HttpServletResponse res, GatewayContext context,
                                      String auditProfile, String key, String username) {
        loginThrottle.recordFailure(key);
        AuditUtil.audit(context, auditProfile, "Login", username, "failure", username, req);
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return AuthUtil.errorJson("Invalid username or password");
    }

    /**
     * POST /auth/logout
     *
     * Invalidates the Bearer token session.
     * Response: {"success": true}
     */
    private static Object doLogout(RequestContext req, HttpServletResponse res,
                                    GatewayContext context, SessionStore sessionStore) {
        String token = AuthUtil.extractBearerToken(req);
        SessionStore.SessionInfo info = sessionStore.getSession(token);
        sessionStore.invalidate(token);
        if (info != null) {
            AuditUtil.audit(context, SettingsUtil.get(context).getDefaultAuditProfile(),
                    "Logout", info.username, "success", info.username, req);
        }

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
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
