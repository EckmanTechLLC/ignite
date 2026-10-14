package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * CORS helpers shared by the API routes and the static file routes.
 *
 * An origin is allowed when it appears in a site's comma-separated corsOrigins (case-insensitive
 * exact match) or that list is "*". Credentials are never allowed (no Access-Control-Allow-Credentials):
 * Bearer and API-key headers are sent explicitly by the caller.
 */
final class CorsUtil {

    static final String ALLOW_HEADERS = "Authorization, Content-Type, X-Ignite-Key, X-Ignite-Site, X-CSRF-Token";
    static final String ALLOW_METHODS = "GET, POST, PUT, DELETE, OPTIONS";
    static final String MAX_AGE = "600";

    private CorsUtil() {}

    /**
     * The Access-Control-Allow-Origin value for this origin and corsOrigins list, or null when the
     * origin is absent or not allowed. "*" yields "*"; a listed origin is echoed back.
     */
    static String matchOrigin(String requestOrigin, String corsOrigins) {
        if (requestOrigin == null || requestOrigin.isBlank()
                || corsOrigins == null || corsOrigins.isBlank()) {
            return null;
        }
        String trimmed = corsOrigins.trim();
        if ("*".equals(trimmed)) {
            return "*";
        }
        for (String allowed : trimmed.split(",")) {
            if (requestOrigin.equalsIgnoreCase(allowed.trim())) {
                return requestOrigin;
            }
        }
        return null;
    }

    /** First match across several sites' corsOrigins (used for preflight, which carries no site). */
    static String matchAnySite(String requestOrigin, List<SiteRecord> sites) {
        for (SiteRecord s : sites) {
            String m = matchOrigin(requestOrigin, s.getCorsOrigins());
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /** Sets Allow-Origin (plus Vary when echoing a specific origin). */
    static void applyOrigin(HttpServletResponse res, String allowOrigin) {
        res.setHeader("Access-Control-Allow-Origin", allowOrigin);
        if (!"*".equals(allowOrigin)) {
            res.addHeader("Vary", "Origin");
        }
    }

    /** Sets the full API CORS header set for an allowed origin. */
    static void applyApi(HttpServletResponse res, String allowOrigin) {
        applyOrigin(res, allowOrigin);
        res.setHeader("Access-Control-Allow-Headers", ALLOW_HEADERS);
        res.setHeader("Access-Control-Allow-Methods", ALLOW_METHODS);
        res.setHeader("Access-Control-Max-Age", MAX_AGE);
    }
}
