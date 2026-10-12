package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Servlet that serves static files for configured Ignite sites.
 *
 * Registered at /ignite-sites/* via WebResourceManager.addServlet().
 * Sites are accessible at http://<gateway>:8088/ignite-sites/<mountPath>/...
 *
 * Features: MIME types, ETag/304, Cache-Control, SPA fallback, env injection,
 * CORS, path traversal guard.
 *
 * GatewayContext is passed via a static field because addServlet() takes a Class,
 * not an instance. Call setContext() before registration and setContext(null) on shutdown.
 */
public class StaticFileServlet extends HttpServlet {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.StaticFileServlet");

    private static volatile GatewayContext gatewayContext;

    /** Called from IgniteGatewayHook.setup() before addServlet(). */
    public static void setContext(GatewayContext context) {
        gatewayContext = context;
    }

    // -------------------------------------------------------------------------
    // GET — static file serving
    // -------------------------------------------------------------------------

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        GatewayContext context = gatewayContext;
        if (context == null) {
            res.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Strip leading slash, then split first segment (mountPath) from file path
        String stripped = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        int slashIdx = stripped.indexOf('/');

        // /ignite-sites/demo (no trailing slash) — redirect to /ignite-sites/demo/
        if (slashIdx < 0) {
            res.sendRedirect(req.getRequestURI() + "/");
            return;
        }

        String mountPath = stripped.substring(0, slashIdx);
        String filePath  = stripped.substring(slashIdx + 1);

        if (mountPath.isEmpty()) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        SiteRecord site = StaticFileRoutes.findSiteByMountPath(context, mountPath);
        if (site == null || !site.isEnabled()) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Apply CORS before any early return
        applyCors(req, res, site.getCorsOrigins());

        // Resolve file on disk
        File baseSitesDir = new File(context.getSystemManager().getDataDir(), "ignite/sites");
        File siteDir      = new File(baseSitesDir, StaticFileRoutes.sanitizeName(site.getName()));
        File serveRoot    = StaticFileRoutes.resolveServePath(siteDir, site.getServePath());

        File file = StaticFileRoutes.resolveFile(serveRoot, filePath);

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
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        serveFile(req, res, file, site);
    }

    // -------------------------------------------------------------------------
    // OPTIONS — CORS preflight
    // -------------------------------------------------------------------------

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse res) throws IOException {
        GatewayContext context = gatewayContext;

        // Try to identify the site from path info to apply the correct CORS policy
        String corsOrigins = null;
        String pathInfo = req.getPathInfo();
        if (pathInfo != null && !pathInfo.isEmpty() && context != null) {
            String stripped = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
            int slashIdx = stripped.indexOf('/');
            String mountPath = slashIdx < 0 ? stripped : stripped.substring(0, slashIdx);
            if (!mountPath.isEmpty()) {
                SiteRecord site = StaticFileRoutes.findSiteByMountPath(context, mountPath);
                if (site != null) corsOrigins = site.getCorsOrigins();
            }
        }

        applyCors(req, res, corsOrigins);
        res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers",
                "Content-Type, Authorization, X-Ignite-API-Key");
        res.setHeader("Access-Control-Max-Age", "86400");
        res.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    // -------------------------------------------------------------------------
    // File serving
    // -------------------------------------------------------------------------

    private void serveFile(HttpServletRequest req, HttpServletResponse res,
                           File file, SiteRecord site) throws IOException {
        String mimeType = StaticFileRoutes.getMimeType(file.getName());
        boolean isHtml  = file.getName().endsWith(".html") || file.getName().endsWith(".htm");
        String etag     = "\"" + file.lastModified() + "-" + file.length() + "\"";

        // ETag: return 304 Not Modified if content unchanged
        String ifNoneMatch = req.getHeader("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            res.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }

        // Cache-Control strategy
        String cacheControl;
        if (isHtml) {
            cacheControl = "no-cache";
        } else if (StaticFileRoutes.HASHED_FILENAME.matcher(file.getName()).find()) {
            cacheControl = "public, max-age=31536000, immutable";
        } else {
            cacheControl = "public, max-age=86400";
        }

        res.setContentType(mimeType);
        res.setHeader("Cache-Control", cacheControl);
        res.setHeader("ETag", etag);

        if (isHtml) {
            // Inject env vars and serve as text
            byte[] raw      = Files.readAllBytes(file.toPath());
            String html     = new String(raw, StandardCharsets.UTF_8);
            String injected = injectEnvScript(html, site, req);
            byte[] out      = injected.getBytes(StandardCharsets.UTF_8);
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
    // CORS
    // -------------------------------------------------------------------------

    private void applyCors(HttpServletRequest req, HttpServletResponse res, String corsOrigins) {
        if (corsOrigins == null || corsOrigins.isBlank()) return;

        String requestOrigin = req.getHeader("Origin");
        if (requestOrigin == null) return;

        String trimmed = corsOrigins.trim();
        if ("*".equals(trimmed)) {
            res.setHeader("Access-Control-Allow-Origin", "*");
            return;
        }

        for (String allowed : trimmed.split(",")) {
            if (requestOrigin.equalsIgnoreCase(allowed.trim())) {
                res.setHeader("Access-Control-Allow-Origin", requestOrigin);
                res.addHeader("Vary", "Origin");
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Env injection
    // -------------------------------------------------------------------------

    private String injectEnvScript(String html, SiteRecord site, HttpServletRequest req) {
        JsonObject env = buildEnvJson(site, req);
        String script  = "<script>window.__IGNITE_ENV__ = " + env.toString() + ";</script>";

        // Prefer injecting just before </head>
        int headClose = html.indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + script + "\n" + html.substring(headClose);
        }

        // Fallback: prepend to entire document
        return script + "\n" + html;
    }

    private JsonObject buildEnvJson(SiteRecord site, HttpServletRequest req) {
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
            String requestUrl = req.getRequestURL().toString();
            // Extract scheme://host:port from the full URL
            int pathStart = requestUrl.indexOf('/', requestUrl.indexOf("//") + 2);
            String gatewayUrl = pathStart > 0 ? requestUrl.substring(0, pathStart) : requestUrl;
            env.addProperty("IGNITE_GATEWAY_URL", gatewayUrl);
        } catch (Exception e) {
            log.debug("Could not determine gateway URL: " + e.getMessage());
        }

        // Inject per-site API key if configured
        String apiKey = site.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            env.addProperty("IGNITE_API_KEY", apiKey);
        }

        return env;
    }
}
