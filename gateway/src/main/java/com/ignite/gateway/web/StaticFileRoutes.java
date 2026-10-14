package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import simpleorm.dataset.SQuery;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Serves static files from git-cloned repos for each configured site.
 *
 * Sites are accessible at /<mountPath>/... relative to the module's route group
 * (full gateway URL: /data/ignite/<mountPath>/...).
 *
 * Features:
 *   - MIME type detection by file extension
 *   - Cache-Control + ETag headers (long cache for hashed filenames, no-cache for HTML)
 *   - SPA routing fallback: unknown paths without extensions get index.html
 *   - Env var injection: window.__IGNITE_ENV__ script prepended to index.html
 *   - CORS headers driven by each site's corsOrigins config
 *   - Path traversal protection via canonical path check
 *
 * Routes registered (after SiteConfigRoutes so specific paths win):
 *   GET     /sites/:name/env  — expose site env vars as JSON
 *   OPTIONS /sites/:name/env  — CORS preflight for env endpoint
 *   GET     /**               — catch-all static file handler
 *   OPTIONS /**               — CORS preflight for static files
 */
public final class StaticFileRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.StaticFileRoutes");

    /** Matches hashed filenames like chunk.a1b2c3d4.js or main.1234abcd.css */
    static final Pattern HASHED_FILENAME =
            Pattern.compile("\\.[a-fA-F0-9]{8,}\\.[a-z]+$");

    static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html",  "text/html; charset=utf-8");
        MIME_TYPES.put("htm",   "text/html; charset=utf-8");
        MIME_TYPES.put("js",    "application/javascript; charset=utf-8");
        MIME_TYPES.put("mjs",   "application/javascript; charset=utf-8");
        MIME_TYPES.put("css",   "text/css; charset=utf-8");
        MIME_TYPES.put("json",  "application/json; charset=utf-8");
        MIME_TYPES.put("map",   "application/json");
        MIME_TYPES.put("svg",   "image/svg+xml");
        MIME_TYPES.put("png",   "image/png");
        MIME_TYPES.put("jpg",   "image/jpeg");
        MIME_TYPES.put("jpeg",  "image/jpeg");
        MIME_TYPES.put("gif",   "image/gif");
        MIME_TYPES.put("webp",  "image/webp");
        MIME_TYPES.put("ico",   "image/x-icon");
        MIME_TYPES.put("woff",  "font/woff");
        MIME_TYPES.put("woff2", "font/woff2");
        MIME_TYPES.put("ttf",   "font/ttf");
        MIME_TYPES.put("otf",   "font/otf");
        MIME_TYPES.put("eot",   "application/vnd.ms-fontobject");
        MIME_TYPES.put("txt",   "text/plain; charset=utf-8");
        MIME_TYPES.put("xml",   "application/xml");
        MIME_TYPES.put("pdf",   "application/pdf");
        MIME_TYPES.put("mp4",   "video/mp4");
        MIME_TYPES.put("webm",  "video/webm");
    }

    private StaticFileRoutes() {}

    // -------------------------------------------------------------------------
    // Route registration
    // -------------------------------------------------------------------------

    /**
     * Mount static file routes onto the given RouteGroup.
     * Must be called AFTER SiteConfigRoutes.mountRoutes so that /sites/** routes
     * take priority over the /** catch-all.
     */
    public static void mountRoutes(RouteGroup routes, GatewayContext context) {

        // GET /sites/:name/env — expose site env vars as JSON
        routes.newRoute("/sites/:name/env")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> getSiteEnv(req, res, context))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // OPTIONS /sites/:name/env — CORS preflight for env endpoint
        routes.newRoute("/sites/:name/env")
                .method(HttpMethod.OPTIONS)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> {
                    handlePreflight(req, res, context);
                    return null;
                })
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // Static file routes at multiple depths under /s/<mount>/...
        // RouteGroup only supports :param (single segment) — no glob wildcards.
        // Register depth 0–6 to cover typical web app paths like /s/demo/assets/js/app.hash.js
        String[] patterns = {
                "/s/:mount",
                "/s/:mount/:p1",
                "/s/:mount/:p1/:p2",
                "/s/:mount/:p1/:p2/:p3",
                "/s/:mount/:p1/:p2/:p3/:p4",
                "/s/:mount/:p1/:p2/:p3/:p4/:p5",
                "/s/:mount/:p1/:p2/:p3/:p4/:p5/:p6",
        };
        for (String pattern : patterns) {
            routes.newRoute(pattern)
                    .method(HttpMethod.GET)
                    .handler((req, res) -> {
                        serveStatic(req, res, context);
                        return null;
                    })
                    .accessControl(AccessControlStrategy.OPEN_ROUTE)
                    .mount();

            routes.newRoute(pattern)
                    .method(HttpMethod.OPTIONS)
                    .handler((req, res) -> {
                        handlePreflight(req, res, context);
                        return null;
                    })
                    .accessControl(AccessControlStrategy.OPEN_ROUTE)
                    .mount();
        }

        log.info("Static file routes mounted (/s/:mount at " + patterns.length + " depth levels).");
    }

    // -------------------------------------------------------------------------
    // Static file handler
    // -------------------------------------------------------------------------

    private static void serveStatic(RequestContext req, HttpServletResponse res,
                                    GatewayContext context) throws Exception {
        // Routes are /s/:mount/... — extract mount path from route param,
        // then derive the file path from the full request URI.
        String mountPath = req.getParameter("mount");
        if (mountPath == null || mountPath.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Extract file path: full URI is /data/ignite/s/<mount>/<filePath>
        // Find "/s/<mount>/" in the URI and take everything after it.
        String uri = req.getRequest().getRequestURI();
        String prefix = "/s/" + mountPath + "/";
        int prefixIdx = uri.indexOf(prefix);
        String filePath = "";
        if (prefixIdx >= 0) {
            filePath = uri.substring(prefixIdx + prefix.length());
        } else if (!uri.endsWith("/")) {
            // Mount root without trailing slash — redirect so relative asset paths resolve correctly
            String qs = req.getRequest().getQueryString();
            res.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            res.setHeader("Location", uri + "/" + (qs != null ? "?" + qs : ""));
            return;
        }

        SiteRecord site = findSiteByMountPath(context, mountPath);
        if (site == null || !site.isEnabled()) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Apply CORS before any early return
        applyCors(req, res, site.getCorsOrigins());

        // Resolve file on disk
        File baseSitesDir = new File(
                context.getSystemManager().getDataDir(), "ignite/sites");
        File siteDir = new File(baseSitesDir, sanitizeName(site.getName()));
        File serveRoot = resolveServePath(siteDir, site.getServePath());
        if (serveRoot == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write("site not found");
            return;
        }

        File file = resolveFile(serveRoot, filePath);

        // SPA fallback: path without a file extension → serve index.html
        if (file == null || !file.isFile()) {
            boolean looksLikeAsset = !filePath.isEmpty()
                    && !filePath.endsWith("/")
                    && filePath.contains(".");
            if (!looksLikeAsset) {
                File indexFile = new File(serveRoot, "index.html");
                if (indexFile.isFile()) {
                    serveFile(req, res, indexFile, site);
                    return;
                }
            }
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        serveFile(req, res, file, site);
    }

    private static void serveFile(RequestContext req, HttpServletResponse res,
                                   File file, SiteRecord site) throws IOException {
        String mimeType   = getMimeType(file.getName());
        boolean isHtml    = file.getName().endsWith(".html") || file.getName().endsWith(".htm");
        // HTML gets the site env injected, so its ETag must change when the env does
        String envJson    = isHtml ? escapeJsonForScript(buildEnvJson(site, req).toString()) : null;
        String etag       = isHtml
                ? "\"" + file.lastModified() + "-" + file.length() + "-" + envHash(envJson) + "\""
                : "\"" + file.lastModified() + "-" + file.length() + "\"";

        // ETag: return 304 Not Modified if content unchanged
        String ifNoneMatch = req.getRequest().getHeader("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            res.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        // Cache-Control strategy
        String cacheControl;
        if (isHtml) {
            cacheControl = "no-cache";
        } else if (HASHED_FILENAME.matcher(file.getName()).find()) {
            cacheControl = "public, max-age=31536000, immutable";
        } else {
            cacheControl = "public, max-age=86400";
        }

        res.setContentType(mimeType);
        res.setHeader("Cache-Control", cacheControl);
        res.setHeader("ETag", etag);

        if (isHtml) {
            // Inject env vars and serve as text
            byte[] raw     = Files.readAllBytes(file.toPath());
            String html    = new String(raw, StandardCharsets.UTF_8);
            String injected = injectEnvScript(html, envJson);
            byte[] out     = injected.getBytes(StandardCharsets.UTF_8);
            res.setContentLength(out.length);
            res.getOutputStream().write(out);
        } else {
            // Stream binary content
            res.setContentLengthLong(file.length());
            try (InputStream in = Files.newInputStream(file.toPath());
                 OutputStream out = res.getOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Env vars endpoint
    // -------------------------------------------------------------------------

    private static Object getSiteEnv(RequestContext req, HttpServletResponse res,
                                     GatewayContext context) {
        String name = req.getParameter("name");
        SiteRecord site = findSiteByName(context, name);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            JsonObject err = new JsonObject();
            err.addProperty("error", "Site not found: " + name);
            return err;
        }
        applyCors(req, res, site.getCorsOrigins());
        return buildEnvJson(site, req);
    }

    // -------------------------------------------------------------------------
    // CORS
    // -------------------------------------------------------------------------

    private static void handlePreflight(RequestContext req, HttpServletResponse res,
                                        GatewayContext context) {
        // Try to identify the site from the :mount route param
        String mountPath = req.getParameter("mount");
        String corsOrigins = null;
        if (mountPath != null && !mountPath.isEmpty()) {
            SiteRecord site = findSiteByMountPath(context, mountPath);
            if (site != null) corsOrigins = site.getCorsOrigins();
        }

        applyCors(req, res, corsOrigins);
        res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers", CorsUtil.ALLOW_HEADERS);
        res.setHeader("Access-Control-Max-Age", "86400");
        res.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static void applyCors(RequestContext req, HttpServletResponse res,
                                   String corsOrigins) {
        String allow = CorsUtil.matchOrigin(req.getRequest().getHeader("Origin"), corsOrigins);
        if (allow != null) {
            CorsUtil.applyOrigin(res, allow);
        }
    }

    // -------------------------------------------------------------------------
    // Env injection
    // -------------------------------------------------------------------------

    /**
     * Escapes JSON for embedding in an inline script: {@code <}, {@code >}, {@code &}, and
     * U+2028/2029 become unicode escapes (valid inside JSON strings, the only place they occur).
     */
    static String escapeJsonForScript(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            switch (c) {
                case '<':      sb.append("\\u003c"); break;
                case '>':      sb.append("\\u003e"); break;
                case '&':      sb.append("\\u0026"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:       sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Short stable hash of the injected env JSON, for the HTML ETag. */
    static String envHash(String envJson) {
        return Integer.toHexString(envJson.hashCode());
    }

    private static String injectEnvScript(String html, String envJson) {
        String script  = "<script>window.__IGNITE_ENV__ = " + envJson + ";</script>";

        // Prefer injecting just before </head>
        int headClose = html.indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + script + "\n" + html.substring(headClose);
        }

        // Fallback: prepend to entire document
        return script + "\n" + html;
    }

    private static JsonObject buildEnvJson(SiteRecord site, RequestContext req) {
        JsonObject env = new JsonObject();

        // Parse and copy configured env vars
        String envVars = site.getEnvVars();
        if (envVars != null && !envVars.isBlank()) {
            try {
                JsonObject parsed = JsonParser.parseString(envVars).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : parsed.entrySet()) {
                    env.add(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                log.warn("Failed to parse envVars for site '" + site.getName()
                        + "': " + e.getMessage());
            }
        }

        // Always inject the gateway base URL
        try {
            java.net.URI uri = req.getRequestUri();
            String gatewayUrl = uri.getScheme() + "://" + uri.getAuthority();
            env.addProperty("IGNITE_GATEWAY_URL", gatewayUrl);
        } catch (Exception e) {
            log.debug("Could not determine gateway URL: " + e.getMessage());
        }

        // Inject the site name so the SPA can send X-Ignite-Site
        env.addProperty("IGNITE_SITE", site.getName());

        // Inject per-site API key if configured
        String apiKey = site.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            env.addProperty("IGNITE_API_KEY", apiKey);
        }

        return env;
    }

    // -------------------------------------------------------------------------
    // File helpers
    // -------------------------------------------------------------------------

    /**
     * Resolve a file path within the serve root directory.
     * Appends index.html for directory paths; prevents path traversal.
     * Returns null on traversal attempt.
     */
    static File resolveFile(File serveRoot, String filePath) throws IOException {
        // Normalize separators and strip leading slashes
        String cleaned = filePath.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }

        String canonicalRoot = serveRoot.getCanonicalPath();

        File target = (cleaned.isEmpty() || cleaned.endsWith("/"))
                ? new File(serveRoot, cleaned + "index.html")
                : new File(serveRoot, cleaned);

        // Path traversal guard
        if (!isWithin(canonicalRoot, target)) {
            log.warn("Path traversal attempt blocked: " + filePath);
            return null;
        }

        // If the target is a directory, serve its index.html
        if (target.isDirectory()) {
            target = new File(target, "index.html");
            // A symlinked directory may point outside the root
            if (!isWithin(canonicalRoot, target)) {
                log.warn("Path traversal attempt blocked: " + filePath);
                return null;
            }
        }

        return target;
    }

    private static boolean isWithin(String canonicalRoot, File f) throws IOException {
        String canonical = f.getCanonicalPath();
        return canonical.equals(canonicalRoot)
                || canonical.startsWith(canonicalRoot + File.separator);
    }

    /** Resolve the subdirectory to serve from within the site directory. */
    static File resolveServePath(File siteDir, String servePath) throws IOException {
        if (servePath == null || servePath.isBlank() || "/".equals(servePath.trim())) {
            return siteDir;
        }
        String path = servePath.trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        String root = siteDir.getCanonicalPath();
        File resolved = new File(siteDir, path);
        String target = resolved.getCanonicalPath();
        if (target.equals(root) || target.startsWith(root + File.separator)) {
            return resolved;
        }
        log.warn("servePath escapes site directory: " + servePath);
        return null;
    }

    /** Determine MIME type from filename extension. */
    static String getMimeType(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) {
            String ext  = filename.substring(dot + 1).toLowerCase();
            String mime = MIME_TYPES.get(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    /**
     * Extract the path relative to this module's RouteGroup base.
     * Uses getRelativeRequestUri() which returns the path without the /data/ignite prefix.
     */
    private static String getRelativePath(RequestContext req) {
        try {
            return req.getRelativeRequestUri().getPath();
        } catch (Exception e) {
            // Fallback to raw servlet path info
            String pathInfo = req.getRequest().getPathInfo();
            if (pathInfo != null) return pathInfo;
            return req.getRequest().getRequestURI();
        }
    }

    // -------------------------------------------------------------------------
    // DB helpers
    // -------------------------------------------------------------------------

    static SiteRecord findSiteByMountPath(GatewayContext context, String mountPath) {
        try {
            PersistenceInterface db = context.getLocalPersistenceInterface();
            List<SiteRecord> all = db.query(new SQuery<>(SiteRecord.META));
            String normalized = stripSlashes(mountPath);
            for (SiteRecord s : all) {
                if (normalized.equals(stripSlashes(s.getMountPath()))) return s;
            }
        } catch (Exception e) {
            log.warn("Error looking up site for mountPath '"
                    + mountPath + "': " + e.getMessage());
        }
        return null;
    }

    private static SiteRecord findSiteByName(GatewayContext context, String name) {
        try {
            PersistenceInterface db = context.getLocalPersistenceInterface();
            List<SiteRecord> all = db.query(new SQuery<>(SiteRecord.META));
            for (SiteRecord s : all) {
                if (name != null && name.equals(s.getName())) return s;
            }
        } catch (Exception e) {
            log.warn("Error looking up site by name '"
                    + name + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Mirror of GitSyncService.sanitizeName — must stay in sync with that method.
     * Converts a site name to a safe filesystem directory name.
     */
    static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /** Strip leading and trailing slashes for consistent mount path comparison. */
    static String stripSlashes(String path) {
        if (path == null) return "";
        String s = path;
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/"))   s = s.substring(0, s.length() - 1);
        return s;
    }
}
