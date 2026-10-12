package com.ignite.gateway.web;

import com.ignite.gateway.SettingsUtil;
import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.auth.security.level.SecurityLevelConfig;
import com.inductiveautomation.ignition.common.auth.web.WebAuthUser;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParseException;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.user.User;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteAccess;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.user.UserSourceProfile;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import simpleorm.dataset.SQuery;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shared authentication utilities for Ignite gateway route handlers.
 *
 * Credential model (ADR-004):
 *   1. A web session actor or a Bearer token from POST /auth/login is a user identity.
 *   2. An API key (enabled site, constant-time match) is read-tier only; it never satisfies write or admin.
 *   3. Query-string credentials (apiKey, token) are honoured only on /sse.
 *   4. Tier checks require a resolved site record; no site means deny.
 *   5. Gateway-wide admin comes from the settings record; a blank level denies.
 */
public final class AuthUtil {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.AuthUtil");

    private static SessionStore sessionStore;

    private AuthUtil() {} // static utility

    public static void setSessionStore(SessionStore store) {
        sessionStore = store;
    }

    /**
     * Extracts a Bearer token from the Authorization header.
     * Returns null if not present or not a Bearer token.
     */
    public static String extractBearerToken(RequestContext req) {
        String authHeader = req.getRequest().getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        if (allowsQueryCredentials(req)) {
            String token = req.getParameter("token");
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        return null;
    }

    /**
     * Query-string credentials are accepted only on the /sse endpoint.
     */
    private static boolean allowsQueryCredentials(RequestContext req) {
        String uri = req.getRequest().getRequestURI();
        return uri != null && uri.endsWith("/sse");
    }

    /**
     * Finds the enabled site whose API key matches (constant-time compare), or null.
     */
    private static SiteRecord findSiteByApiKey(GatewayContext ctx, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            PersistenceInterface db = ctx.getLocalPersistenceInterface();
            List<SiteRecord> sites = db.query(new SQuery<>(SiteRecord.META));
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            for (SiteRecord site : sites) {
                String siteKey = site.getApiKey();
                if (!site.isEnabled() || siteKey == null || siteKey.isBlank()) {
                    continue;
                }
                if (MessageDigest.isEqual(siteKey.getBytes(StandardCharsets.UTF_8), keyBytes)) {
                    return site;
                }
            }
        } catch (Exception e) {
            log.warn("Error validating API key", e);
        }
        return null;
    }

    /**
     * The only source of web-session identity (ADR-004 section 1): the platform web session's user name.
     * The request context actor is never used because it is non-blank for anonymous requests on 8.3.
     */
    public static Optional<String> webSessionUser(RequestContext req) {
        try {
            return WebUiSession.find(req)
                    .flatMap(s -> s.getUserContext().getWebAuthUser())
                    .map(WebAuthUser::getUserName)
                    .filter(n -> !n.isBlank() && !"unknown".equalsIgnoreCase(n));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Returns true if the request carries valid credentials.
     */
    public static boolean isAuthorized(RequestContext req, GatewayContext context) {
        // Allow requests made within an authenticated Ignition session
        if (webSessionUser(req).isPresent()) {
            return true;
        }

        // Check Bearer token
        if (sessionStore != null) {
            String token = extractBearerToken(req);
            if (token != null && sessionStore.getSession(token) != null) {
                return true;
            }
        }

        // Check API key
        String apiKey = extractApiKey(req);
        if (apiKey == null) {
            return false;
        }

        return findSiteByApiKey(context, apiKey) != null;
    }

    /**
     * Returns the actor identity string for logging/audit.
     * Order: Bearer session username, web-session user, "api-key:&lt;siteName&gt;", "anonymous".
     */
    public static String getActorIdentity(RequestContext req, GatewayContext context) {
        // Check Bearer token
        if (sessionStore != null) {
            String token = extractBearerToken(req);
            if (token != null) {
                SessionStore.SessionInfo info = sessionStore.getSession(token);
                if (info != null) {
                    return info.username;
                }
            }
        }
        Optional<String> webUser = webSessionUser(req);
        if (webUser.isPresent()) {
            return webUser.get();
        }
        String apiKey = extractApiKey(req);
        if (apiKey != null) {
            SiteRecord keySite = findSiteByApiKey(context, apiKey);
            return keySite != null ? "api-key:" + keySite.getName() : "api-key:unknown";
        }
        return "anonymous";
    }

    /**
     * Extracts the API key from the X-Ignite-Key header, or the apiKey query param on /sse only.
     * Returns null if not present.
     */
    public static String extractApiKey(RequestContext req) {
        String apiKey = req.getRequest().getHeader("X-Ignite-Key");
        if ((apiKey == null || apiKey.isBlank()) && allowsQueryCredentials(req)) {
            apiKey = req.getParameter("apiKey");
        }
        return (apiKey != null && !apiKey.isBlank()) ? apiKey : null;
    }

    /**
     * Returns the roles (security levels) for the authenticated session user.
     * Union of the web session's roles/security levels and the configured login auth profile roles.
     *
     * Returns an empty list if:
     *  - No active Ignition session (API key auth or anonymous)
     *  - User not found in the login auth profile
     *  - Any error occurs during lookup
     */
    public static List<String> getUserSecurityLevels(RequestContext req, GatewayContext context) {
        // Check Bearer token first — roles were captured at login time
        if (sessionStore != null) {
            String token = extractBearerToken(req);
            if (token != null) {
                SessionStore.SessionInfo info = sessionStore.getSession(token);
                if (info != null) {
                    return info.roles != null ? info.roles : Collections.emptyList();
                }
            }
        }
        Set<String> levels = new LinkedHashSet<>();
        try {
            Optional<? extends WebUiSession> session = WebUiSession.find(req);
            if (session.isPresent()) {
                session.get().getUserContext().getWebAuthUser()
                        .map(WebAuthUser::getRoles)
                        .ifPresent(levels::addAll);
                collectLevelNames(session.get().getUserContext().getSecurityLevels(), levels);
            }
        } catch (Exception e) {
            log.warn("Error reading web session security levels", e);
        }
        String username = webSessionUser(req).orElse(null);
        if (username == null) {
            return new ArrayList<>(levels);
        }
        try {
            String profileName = SettingsUtil.get(context).getLoginAuthProfile();
            UserSourceProfile profile = context.getUserSourceManager().getProfile(profileName);
            if (profile != null) {
                Optional<User> user = profile.getUser(username);
                if (user.isPresent()) {
                    List<String> roles = user.get().getRoles();
                    if (roles != null) {
                        levels.addAll(roles);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error looking up security levels for user '" + username + "'", e);
        }
        return new ArrayList<>(levels);
    }

    private static void collectLevelNames(Collection<SecurityLevelConfig> configs, Set<String> out) {
        if (configs == null) {
            return;
        }
        for (SecurityLevelConfig c : configs) {
            out.add(c.getName());
            collectLevelNames(c.getChildren(), out);
        }
    }

    /** Permission-only (no CSRF) write check; used for every method. */
    private static final AccessControlStrategy CONFIG_WRITE_PERMISSION =
            new WebUiSession.WebUiSessionAccessControl(PermissionType.WRITE);

    /**
     * True if the request carries an Ignition config-UI web session permitted to write gateway config.
     * Two-step rule: (1) a web session is present and holds the config-write permission (all methods);
     * (2) for unsafe methods (not GET/HEAD/OPTIONS) the platform CSRF check must also pass
     * (X-CSRF-Token equal to the session's token). Never throws.
     */
    public static boolean isGatewayConfigWriter(RequestContext req) {
        try {
            if (!WebUiSession.find(req).isPresent()
                    || CONFIG_WRITE_PERMISSION.canAccess(req) != RouteAccess.GRANTED) {
                return false;
            }
            HttpMethod method = req.getMethod();
            if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS) {
                return true;
            }
            return WebUiSession.SESSION_WRITE.canAccess(req) == RouteAccess.GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if the request has a user identity: a web session actor or a valid Bearer session.
     * An API-key-only caller is never a user.
     */
    public static boolean hasUserIdentity(RequestContext req, GatewayContext ctx) {
        if (webSessionUser(req).isPresent()) {
            return true;
        }
        if (sessionStore != null) {
            String token = extractBearerToken(req);
            return token != null && sessionStore.getSession(token) != null;
        }
        return false;
    }

    /**
     * Write-tier check for a site. Denies when site is null or the caller is not a user.
     */
    public static boolean canWrite(RequestContext req, GatewayContext ctx, SiteRecord site) {
        if (site == null || !hasUserIdentity(req, ctx)) {
            return false;
        }
        String level = site.getWriteSecurityLevel();
        if (level == null || level.isBlank()) {
            return true;
        }
        return userHasSecurityLevel(req, ctx, level);
    }

    /**
     * Admin-tier check for a site. Denies when site is null or the caller is not a user.
     */
    public static boolean canAdmin(RequestContext req, GatewayContext ctx, SiteRecord site) {
        if (site == null || !hasUserIdentity(req, ctx)) {
            return false;
        }
        String level = site.getAdminSecurityLevel();
        if (level == null || level.isBlank()) {
            return true;
        }
        return userHasSecurityLevel(req, ctx, level);
    }

    /**
     * Gateway-wide admin check. A web session permitted to write gateway config is always admin;
     * otherwise a blank settings admin level denies (ADR-004 section 2).
     */
    public static boolean canAdminGateway(RequestContext req, GatewayContext ctx) {
        if (isGatewayConfigWriter(req)) {
            return true;
        }
        if (!hasUserIdentity(req, ctx)) {
            return false;
        }
        String level = SettingsUtil.get(ctx).getAdminSecurityLevel();
        if (level == null || level.isBlank()) {
            return false;
        }
        return userHasSecurityLevel(req, ctx, level);
    }

    /**
     * Resolves the request's site, or null if unresolved (callers should respond 403).
     */
    public static SiteRecord requireSite(RequestContext req, GatewayContext ctx) {
        return getSiteForRequest(req, ctx);
    }

    /**
     * Returns true if the session user's roles contain the required security level
     * (case-insensitive match).
     */
    private static boolean userHasSecurityLevel(RequestContext req, GatewayContext context, String level) {
        for (String role : getUserSecurityLevels(req, context)) {
            if (level.equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the SiteRecord associated with the request.
     * Checks (in order):
     *   1. API key match (X-Ignite-Key header or apiKey param) → returns matching site
     *   2. X-Ignite-Site header or site query param → returns site by name
     * Returns null if no site can be determined.
     */
    public static SiteRecord getSiteForRequest(RequestContext req, GatewayContext context) {
        try {
            PersistenceInterface db = context.getLocalPersistenceInterface();
            List<SiteRecord> sites = db.query(new SQuery<>(SiteRecord.META));

            // Try API key first — return the site whose key matches
            String apiKey = extractApiKey(req);
            SiteRecord keySite = findSiteByApiKey(context, apiKey);
            if (keySite != null) {
                return keySite;
            }

            // Try explicit site name from X-Ignite-Site header or site query param
            String siteName = req.getRequest().getHeader("X-Ignite-Site");
            if (siteName == null || siteName.isBlank()) {
                siteName = req.getParameter("site");
            }
            if (siteName != null && !siteName.isBlank()) {
                for (SiteRecord site : sites) {
                    if (siteName.equals(site.getName())) {
                        return site;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error resolving site for request", e);
        }
        return null;
    }

    /**
     * Builds a JSON error object: {"error": "message"}
     */
    public static JsonObject errorJson(String message) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", message);
        return obj;
    }

    /**
     * Thrown by request parsing when client input is malformed; handler wrappers map it to 400.
     * The message is client-facing, so it must never contain internal exception text.
     */
    public static final class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /**
     * Sets 400 and returns {"error": message}.
     */
    public static JsonObject badRequest(HttpServletResponse res, String message) {
        res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return errorJson(message);
    }

    /**
     * Logs the failure server-side under a short ref, sets 500 and returns
     * {"error":"internal error","ref":ref}. No exception detail reaches the client.
     */
    public static JsonObject internalError(HttpServletResponse res, LoggerEx logger, String where, Throwable t) {
        String ref = UUID.randomUUID().toString().substring(0, 8);
        logger.error(where + " [" + ref + "]", t);
        res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        JsonObject obj = errorJson("internal error");
        obj.addProperty("ref", ref);
        return obj;
    }

    /**
     * Reads the request body as a JSON object. Throws BadRequestException for an
     * empty, unparseable or non-object body.
     */
    public static JsonObject parseJsonObject(RequestContext req) {
        String body;
        try {
            body = req.readBody();
        } catch (IOException e) {
            throw new BadRequestException("Invalid JSON body");
        }
        if (body == null || body.isBlank()) {
            throw new BadRequestException("Request body required");
        }
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new BadRequestException("Invalid JSON body");
        }
    }
}
