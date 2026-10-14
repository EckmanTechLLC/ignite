package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.StreamingDatasetWriter;
import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.browsing.Results;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode;
import com.inductiveautomation.ignition.common.sqltags.history.BasicTagHistoryQueryParams;
import com.inductiveautomation.ignition.common.sqltags.history.ReturnFormat;
import com.inductiveautomation.ignition.common.tags.browsing.NodeDescription;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.historian.TagHistoryManager;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.tags.model.GatewayTagManager;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * REST route handlers for Ignition tag operations.
 *
 * Routes are mounted under the module's route group (alias "ignite"):
 *   GET  /tags/read?paths=path1,path2,...              — read current tag values
 *   POST /tags/write                                   — write tag values
 *   GET  /tags/browse?path=...                         — browse tag tree children
 *   GET  /tags/history?paths=...&start=...&end=...&returnSize=...  — query tag history
 *
 * Auth: X-Ignite-Key header or apiKey query param matched against any site's
 * configured API key. An active Ignition session (non-empty actor) is also accepted.
 *
 * Tag scope (ADR-004 A1): every path must lie in the resolved site's TagPathPatterns, else 403
 * {"error":"tag path not permitted"} (all-or-nothing); browse results are filtered.
 */
public final class TagRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.TagRoutes");

    /** Read/write timeout for async tag operations. */
    private static final long TAG_TIMEOUT_SEC = 10;

    /** Upper bound on history rows a caller may request. */
    private static final int MAX_RETURN_SIZE = 10_000;

    private TagRoutes() {}

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

        // GET /tags/read?paths=path1,path2,...
        routes.newRoute("/tags/read")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> readTags(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // POST /tags/write   body: [{path, value}, ...]
        routes.newRoute("/tags/write")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> writeTags(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // GET /tags/browse?path=...
        routes.newRoute("/tags/browse")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> browseTags(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // GET /tags/history?paths=...&start=ISO&end=ISO&returnSize=N
        routes.newRoute("/tags/history")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> queryHistory(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /** Read current values for one or more tag paths. */
    private static Object readTags(RequestContext req, HttpServletResponse res,
                                   GatewayContext context) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }

        String pathsParam = req.getParameter("paths");
        if (pathsParam == null || pathsParam.isBlank()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Missing required parameter: paths");
        }

        List<TagPath> tagPaths = parsePaths(pathsParam);
        Object denied = checkScope(res, site, tagPaths);
        if (denied != null) {
            return denied;
        }
        GatewayTagManager tagManager = context.getTagManager();
        List<QualifiedValue> values = tagManager.readAsync(tagPaths)
                .get(TAG_TIMEOUT_SEC, TimeUnit.SECONDS);

        JsonArray tags = new JsonArray();
        for (int i = 0; i < tagPaths.size(); i++) {
            JsonObject tag = new JsonObject();
            tag.addProperty("path", tagPaths.get(i).toString());
            QualifiedValue qv = values.get(i);
            tag.add("value", serializeValue(qv.getValue()));
            tag.addProperty("quality", qv.getQuality().getName());
            Date ts = qv.getTimestamp();
            tag.addProperty("timestamp", ts != null ? ts.toInstant().toString() : null);
            tags.add(tag);
        }

        JsonObject result = new JsonObject();
        result.add("tags", tags);
        return result;
    }

    /** Write values to one or more tag paths. Body: [{path, value}, ...] */
    private static Object writeTags(RequestContext req, HttpServletResponse res,
                                    GatewayContext context) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }
        if (!AuthUtil.canWrite(req, context, site)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("write access required");
        }

        String body = req.readBody();
        if (body == null || body.isBlank()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Request body is required");
        }

        JsonArray writes;
        try {
            writes = JsonParser.parseString(body).getAsJsonArray();
        } catch (Exception e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Invalid JSON body: expected array of {path, value}");
        }

        List<TagPath> tagPaths = new ArrayList<>();
        List<Object> writeValues = new ArrayList<>();

        for (JsonElement el : writes) {
            if (!el.isJsonObject()) {
                throw new AuthUtil.BadRequestException("Each write entry must be an object");
            }
            JsonObject item = el.getAsJsonObject();
            if (!item.has("path") || !item.has("value")) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return AuthUtil.errorJson("Each write entry must have 'path' and 'value'");
            }
            tagPaths.add(parseTagPath(stringField(item, "path")));
            writeValues.add(deserializeValue(item.get("value")));
        }

        Object denied = checkScope(res, site, tagPaths);
        if (denied != null) {
            return denied;
        }
        GatewayTagManager tagManager = context.getTagManager();
        List<QualityCode> results = tagManager.writeAsync(tagPaths, writeValues)
                .get(TAG_TIMEOUT_SEC, TimeUnit.SECONDS);

        // Audit each tag write
        String actor = AuthUtil.getActorIdentity(req, context);
        String auditProfile = AuditUtil.getAuditProfileForRequest(req, context);
        for (int i = 0; i < tagPaths.size(); i++) {
            AuditUtil.audit(context, auditProfile, "Tag Write",
                    tagPaths.get(i).toString(),
                    String.valueOf(writeValues.get(i)),
                    actor, req);
        }

        JsonArray resultArr = new JsonArray();
        for (int i = 0; i < tagPaths.size(); i++) {
            JsonObject r = new JsonObject();
            r.addProperty("path", tagPaths.get(i).toString());
            r.addProperty("quality", results.get(i).getName());
            resultArr.add(r);
        }

        JsonObject result = new JsonObject();
        result.add("results", resultArr);
        return result;
    }

    /** Browse the tag tree at the given path (defaults to root). */
    private static Object browseTags(RequestContext req, HttpServletResponse res,
                                     GatewayContext context) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }

        String pathStr = req.getParameter("path");
        if (pathStr == null) pathStr = "";

        TagPath tagPath = parseTagPath(pathStr);

        TagScope scope = TagScope.forSite(site);
        boolean root = pathStr.isEmpty();
        if (root ? !scope.allowsRootBrowse() : !scope.allowsFolderBrowse(tagPath)) {
            return TagScope.deny(res, log, site, pathStr);
        }

        GatewayTagManager tagManager = context.getTagManager();
        Results<NodeDescription> browseResults = tagManager
                .browseAsync(tagPath, BrowseFilter.NONE)
                .get(TAG_TIMEOUT_SEC, TimeUnit.SECONDS);
        if (browseResults == null || browseResults.getResults() == null) {
            // browse of a non-existent folder/provider returns no result list
            throw new AuthUtil.BadRequestException("tag folder not found: " + pathStr);
        }

        JsonArray children = new JsonArray();
        for (NodeDescription node : browseResults.getResults()) {
            JsonObject child = new JsonObject();
            child.addProperty("name", node.getName());
            TagPath fullPath = node.getFullPath();
            String pathString;
            if (fullPath != null && !fullPath.toString().isEmpty()) {
                pathString = fullPath.toString();
            } else if (pathStr.isEmpty()) {
                // Root-level nodes are tag providers — path must be [providerName]
                pathString = "[" + node.getName() + "]";
            } else {
                // Child node with no fullPath — construct from parent + name
                pathString = pathStr + "/" + node.getName();
            }
            if (!scope.isUnrestricted()
                    && !(root ? scope.allowsProvider(node.getName())
                              : scope.allows(pathString) || scope.allowsFolderBrowse(pathString))) {
                continue;
            }
            child.addProperty("path", pathString);
            child.addProperty("hasChildren", node.hasChildren());
            if (node.getObjectType() != null) {
                child.addProperty("type", node.getObjectType().toString());
            }
            if (node.getDataType() != null) {
                child.addProperty("dataType", node.getDataType().name());
            }
            children.add(child);
        }

        JsonObject result = new JsonObject();
        result.addProperty("path", pathStr);
        result.add("children", children);
        result.addProperty("count", scope.isUnrestricted() ? browseResults.getReturnedSize() : children.size());
        return result;
    }

    /**
     * Query tag history for a time range.
     *
     * Query params:
     *   paths      — comma-separated tag paths (required)
     *   start      — ISO 8601 start time (default: 1 hour ago)
     *   end        — ISO 8601 end time (default: now)
     *   returnSize — max rows to return (default: 100)
     */
    private static Object queryHistory(RequestContext req, HttpServletResponse res,
                                       GatewayContext context) throws Exception {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }

        String pathsParam = req.getParameter("paths");
        if (pathsParam == null || pathsParam.isBlank()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Missing required parameter: paths");
        }

        // Parse time range
        String startStr = req.getParameter("start");
        String endStr   = req.getParameter("end");
        String sizeStr  = req.getParameter("returnSize");

        Date endDate   = endStr   != null ? Date.from(parseInstant(endStr, "end"))   : new Date();
        Date startDate = startStr != null ? Date.from(parseInstant(startStr, "start"))
                : new Date(endDate.getTime() - 3_600_000L);
        int returnSize = 100;
        if (sizeStr != null) {
            try {
                returnSize = Integer.parseInt(sizeStr.trim());
            } catch (NumberFormatException e) {
                throw new AuthUtil.BadRequestException("invalid returnSize");
            }
            if (returnSize < 1 || returnSize > MAX_RETURN_SIZE) {
                throw new AuthUtil.BadRequestException("returnSize must be between 1 and " + MAX_RETURN_SIZE);
            }
        }

        List<TagPath> tagPaths = parsePaths(pathsParam);
        Object denied = checkScope(res, site, tagPaths);
        if (denied != null) {
            return denied;
        }

        TagHistoryManager histManager = context.getTagHistoryManager();
        if (histManager == null) {
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return AuthUtil.errorJson("Tag history manager is not available");
        }

        // Build query params — Wide format: columns are [t_stamp, tag1, tag2, ...]
        BasicTagHistoryQueryParams params = new BasicTagHistoryQueryParams(
                tagPaths, startDate, endDate, returnSize,
                AggregationMode.LastValue, ReturnFormat.Wide,
                null, null
        );

        // Collect results via streaming writer
        final String[][] columnHolder = {null};
        final List<Object[]> rows = new ArrayList<>();

        histManager.queryHistory(params, new StreamingDatasetWriter() {
            @Override
            public void initialize(String[] colNames, Class<?>[] colTypes,
                                   boolean firstDataset, int rowEstimate) {
                columnHolder[0] = colNames;
            }

            @Override
            public void write(Object[] row, QualityCode[] qualities) {
                rows.add(row.clone());
            }

            @Override
            public void finish() {}

            @Override
            public void finishWithError(Exception e) {
                log.warn("Tag history query finished with error", e);
            }
        });

        // Format output
        String[] colNames = columnHolder[0] != null ? columnHolder[0] : new String[0];
        JsonArray colArray = new JsonArray();
        for (String c : colNames) {
            colArray.add(c);
        }

        JsonArray rowArray = new JsonArray();
        for (Object[] row : rows) {
            JsonArray jsonRow = new JsonArray();
            for (Object cell : row) {
                jsonRow.add(serializeCellValue(cell));
            }
            rowArray.add(jsonRow);
        }

        JsonObject result = new JsonObject();
        result.add("columns", colArray);
        result.add("rows", rowArray);
        result.addProperty("rowCount", rows.size());
        return result;
    }

    // -------------------------------------------------------------------------
    // Value helpers
    // -------------------------------------------------------------------------

    /** All-or-nothing scope check: null when every path is in the site's tag scope, else the 403 body. */
    private static Object checkScope(HttpServletResponse res, SiteRecord site, List<TagPath> paths) {
        TagScope scope = TagScope.forSite(site);
        for (TagPath p : paths) {
            if (!scope.allows(p)) {
                return TagScope.deny(res, log, site, p);
            }
        }
        return null;
    }

    /** Parse a comma-separated list of tag path strings. */
    private static List<TagPath> parsePaths(String param) {
        String[] parts = param.split(",");
        List<TagPath> paths = new ArrayList<>(parts.length);
        for (String p : parts) {
            paths.add(parseTagPath(p.trim()));
        }
        return paths;
    }

    /** Parse a single tag path; a null parse result is a client error. */
    private static TagPath parseTagPath(String path) {
        TagPath parsed = TagPathParser.parseSafe(path);
        if (parsed == null) {
            throw new AuthUtil.BadRequestException("invalid tag path: " + path);
        }
        return parsed;
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new AuthUtil.BadRequestException("invalid " + field);
        }
    }

    private static String stringField(JsonObject obj, String field) {
        try {
            return obj.get(field).getAsString();
        } catch (RuntimeException e) {
            throw new AuthUtil.BadRequestException("invalid " + field);
        }
    }

    /**
     * Serialize a tag value to a JSON element.
     * Handles Number, Boolean, Date, String, and null; falls back to toString().
     */
    private static JsonElement serializeValue(Object value) {
        if (value == null) {
            return com.inductiveautomation.ignition.common.gson.JsonNull.INSTANCE;
        }
        if (value instanceof Number) {
            return new com.inductiveautomation.ignition.common.gson.JsonPrimitive((Number) value);
        }
        if (value instanceof Boolean) {
            return new com.inductiveautomation.ignition.common.gson.JsonPrimitive((Boolean) value);
        }
        if (value instanceof Date) {
            return new com.inductiveautomation.ignition.common.gson.JsonPrimitive(
                    ((Date) value).toInstant().toString());
        }
        return new com.inductiveautomation.ignition.common.gson.JsonPrimitive(value.toString());
    }

    /**
     * Serialize a history cell value to a JSON string.
     * Timestamps become ISO 8601; nulls become empty string; others use toString().
     */
    private static String serializeCellValue(Object cell) {
        if (cell == null) return null;
        if (cell instanceof Date) return ((Date) cell).toInstant().toString();
        return cell.toString();
    }

    /**
     * Deserialize a JSON element to a Java value suitable for tag writes.
     * Numbers stay as Double, booleans as Boolean, strings as String.
     */
    private static Object deserializeValue(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            com.inductiveautomation.ignition.common.gson.JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isBoolean()) return prim.getAsBoolean();
            if (prim.isNumber())  return prim.getAsDouble();
            return prim.getAsString();
        }
        return el.toString();
    }

}
