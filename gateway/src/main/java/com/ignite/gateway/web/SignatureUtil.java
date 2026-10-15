package com.ignite.gateway.web;

import com.ignite.gateway.SettingsUtil;
import com.ignite.gateway.records.IgniteSettingsRecord;
import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.user.AuthenticatedUser;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Set;

/**
 * Electronic signatures for state-changing routes (ADR-006, 21 CFR Part 11).
 *
 * The first half is pure (body parsing, actor check, audit-value formatting) and unit tested;
 * {@link #gate} is the gateway-facing half that verifies the credential and enforces RequireSignature.
 */
public final class SignatureUtil {

    public static final int MAX_REASON = 200;
    public static final int MAX_AUDIT_VALUE = 255;
    public static final Set<String> MEANINGS =
            Set.of("APPROVED", "REVIEWED", "CHANGED", "ACKNOWLEDGED", "OVERRIDE", "OTHER");

    private SignatureUtil() {}

    /** A parsed (not yet verified) signature. The meaning is normalised to upper case. */
    public static final class Signature {
        public final String username;
        public final String password;
        public final String meaning;

        Signature(String username, String password, String meaning) {
            this.username = username;
            this.password = password;
            this.meaning = meaning;
        }
    }

    /** Optional signature and reason from a request body; either may be null. */
    public static final class Request {
        public final Signature signature;
        public final String reason;

        Request(Signature signature, String reason) {
            this.signature = signature;
            this.reason = reason;
        }
    }

    // -------------------------------------------------------------------------
    // Pure helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the optional "signature" and "reason" members. Absent members yield nulls;
     * a present but malformed one throws BadRequestException (client-safe message).
     */
    public static Request parse(JsonObject body) {
        String reason = null;
        if (body != null && body.has("reason") && !body.get("reason").isJsonNull()) {
            reason = text(body.get("reason"), "reason");
            if (reason.length() > MAX_REASON) {
                throw new AuthUtil.BadRequestException("reason too long (max " + MAX_REASON + " characters)");
            }
            if (reason.isBlank()) {
                reason = null;
            }
        }

        Signature sig = null;
        if (body != null && body.has("signature") && !body.get("signature").isJsonNull()) {
            JsonElement el = body.get("signature");
            if (!el.isJsonObject()) {
                throw new AuthUtil.BadRequestException("signature must be an object");
            }
            JsonObject o = el.getAsJsonObject();
            String username = member(o, "username");
            String password = member(o, "password");
            String meaning = member(o, "meaning");
            if (username == null || password == null || meaning == null) {
                throw new AuthUtil.BadRequestException("signature requires username, password and meaning");
            }
            String m = meaning.trim().toUpperCase(java.util.Locale.ROOT);
            if (!MEANINGS.contains(m)) {
                throw new AuthUtil.BadRequestException(
                        "signature meaning must be one of APPROVED, REVIEWED, CHANGED, ACKNOWLEDGED, OVERRIDE, OTHER");
            }
            sig = new Signature(username, password, m);
        }
        return new Request(sig, reason);
    }

    /** True when the signature names the authenticated actor (case-insensitive, like user-source lookup). */
    public static boolean matchesActor(Signature sig, String actor) {
        if (sig == null || actor == null || sig.username == null) {
            return false;
        }
        if (actor.startsWith("api-key:") || "anonymous".equals(actor)) {
            return false;
        }
        return sig.username.trim().equalsIgnoreCase(actor);
    }

    /** base, then " | signed: MEANING" when signed, then " | reason: text" when given; bounded to 255 chars. */
    public static String actionValue(String base, Signature sig, String reason) {
        StringBuilder sb = new StringBuilder(base == null ? "" : base);
        if (sig != null) {
            sb.append(" | signed: ").append(sig.meaning);
        }
        if (reason != null && !reason.isBlank()) {
            sb.append(" | reason: ").append(reason);
        }
        return truncate(sb.toString());
    }

    /** Cuts to MAX_AUDIT_VALUE characters, ending in an ellipsis when cut. */
    public static String truncate(String s) {
        if (s == null || s.length() <= MAX_AUDIT_VALUE) {
            return s;
        }
        return s.substring(0, MAX_AUDIT_VALUE - 1) + "…";
    }

    private static String member(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        String v = text(o.get(key), "signature." + key);
        return v.isBlank() ? null : v;
    }

    private static String text(JsonElement el, String name) {
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new AuthUtil.BadRequestException(name + " must be a string");
        }
        return el.getAsString();
    }

    // -------------------------------------------------------------------------
    // Gateway-facing
    // -------------------------------------------------------------------------

    /**
     * Enforces the signature rules before a state change runs. Returns null to proceed, or the
     * error body to return (status already set): 403 signature required / actor mismatch / invalid,
     * 429 + Retry-After when locked out. Credentials are checked exactly as /auth/login does and
     * share its lockout counters.
     */
    public static Object gate(RequestContext req, HttpServletResponse res, GatewayContext context,
                              SiteRecord site, Request sr) {
        if (sr.signature == null) {
            if (site != null && site.isRequireSignature()) {
                res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return AuthUtil.errorJson("signature required");
            }
            return null;
        }

        String actor = AuthUtil.getActorIdentity(req, context);
        if (!matchesActor(sr.signature, actor)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("signature actor mismatch");
        }

        IgniteSettingsRecord settings = SettingsUtil.get(context);
        String key = LoginThrottle.key(sr.signature.username, req.getRequest().getRemoteAddr());
        int lockoutSeconds = settings.getLoginLockoutSeconds();
        if (AuthRoutes.loginThrottle.isLocked(key, settings.getLoginMaxFailures(), lockoutSeconds)) {
            res.setHeader("Retry-After", String.valueOf(lockoutSeconds));
            res.setStatus(429);
            return AuthUtil.errorJson("Too many failed attempts; try again later");
        }

        AuthenticatedUser user = AuthRoutes.authenticateUser(context, settings,
                sr.signature.username, sr.signature.password);
        if (user == null) {
            AuthRoutes.loginThrottle.recordFailure(key);
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("signature invalid");
        }
        AuthRoutes.loginThrottle.clear(key);
        return null;
    }
}
