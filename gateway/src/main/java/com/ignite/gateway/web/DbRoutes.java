package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.Dataset;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQuery;
import com.inductiveautomation.ignition.common.db.namedquery.NamedQueryManager;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonNull;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.gson.JsonPrimitive;
import com.inductiveautomation.ignition.common.resourcecollection.ResourcePath;
import com.inductiveautomation.ignition.common.resourcecollection.RuntimeResourceCollection;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.datasource.Datasource;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST route handlers for Ignition database operations.
 *
 * Routes are mounted under the module's route group (alias "ignite"):
 *   GET  /db/connections             — list all configured database connections
 *   POST /db/query                   — execute a named query (raw SQL is not supported)
 *
 * Request body for POST /db/query:
 *   { "query": "ProjectName/Queries/GetParts", "type": "named", "params": {"id": 1} }
 *
 * The "query" field must be "ProjectName/query/path" — the portion before the
 * first "/" is used as the Ignition project name. "type" is optional; any value
 * other than "named" is rejected. Callers without write tier run queries on a
 * read-only connection. Every execution is audited.
 *
 * Auth: X-Ignite-Key header or apiKey query param matched against any site's
 * configured API key. An active Ignition session (non-empty actor) is also accepted.
 */
public final class DbRoutes {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.DbRoutes");

    /** Maximum rows returned by a named query. */
    private static final int MAX_ROWS = 1000;

    private DbRoutes() {}

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

        // GET /db/connections
        routes.newRoute("/db/connections")
                .method(HttpMethod.GET)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> listConnections(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();

        // POST /db/query
        routes.newRoute("/db/query")
                .method(HttpMethod.POST)
                .type(RouteGroup.TYPE_JSON)
                .handler((req, res) -> guarded(req, res, () -> executeQuery(req, res, context)))
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    /** List all configured database connections and their current status. */
    private static Object listConnections(RequestContext req, HttpServletResponse res,
                                          GatewayContext context) {
        if (!AuthUtil.isAuthorized(req, context)) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return AuthUtil.errorJson("Unauthorized");
        }
        SiteRecord site = AuthUtil.requireSite(req, context);
        if (site == null) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return AuthUtil.errorJson("site required: send X-Ignite-Site or an API key");
        }

        List<Datasource> datasources = context.getDatasourceManager().getDatasources();

        JsonArray connections = new JsonArray();
        for (Datasource ds : datasources) {
            JsonObject conn = new JsonObject();
            conn.addProperty("name", ds.getName());
            conn.addProperty("description", ds.getDescription());
            conn.addProperty("status", ds.getStatus() != null ? ds.getStatus().toString() : "unknown");
            if (ds.getVendor() != null) {
                conn.addProperty("vendor", ds.getVendor().toString());
            }
            conn.addProperty("maxConnections", ds.getMaxConnections());
            conn.addProperty("activeConnections", ds.getActiveConnections());
            Throwable problem = ds.getProblem();
            if (problem != null) {
                conn.addProperty("problem", problem.getMessage());
            }
            connections.add(conn);
        }

        JsonObject result = new JsonObject();
        result.add("connections", connections);
        result.addProperty("count", connections.size());
        return result;
    }

    /**
     * Execute an Ignition named query.
     *
     * Body: { "query": "ProjectName/path/to/query", "type": "named" (optional), "params": {} }
     * Raw SQL is not supported; "connection" in the body is ignored.
     */
    private static Object executeQuery(RequestContext req, HttpServletResponse res,
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

        String body = req.readBody();
        if (body == null || body.isBlank()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Request body is required");
        }

        JsonObject bodyJson;
        try {
            bodyJson = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Invalid JSON body");
        }

        String queryType;
        String queryStr;
        try {
            queryType = bodyJson.has("type") ? bodyJson.get("type").getAsString() : "named";
            queryStr  = bodyJson.has("query") ? bodyJson.get("query").getAsString() : null;
        } catch (RuntimeException e) {
            throw new AuthUtil.BadRequestException("invalid type or query");
        }

        if (!"named".equals(queryType)) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("raw SQL is not supported; use type=named");
        }

        if (queryStr == null || queryStr.isBlank()) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Missing required field: query");
        }

        JsonObject paramsJson = bodyJson.has("params") && bodyJson.get("params").isJsonObject()
                ? bodyJson.getAsJsonObject("params")
                : new JsonObject();

        boolean write = AuthUtil.canWrite(req, context, site);
        return executeNamedQuery(req, res, context, queryStr, paramsJson, !write);
    }

    // -------------------------------------------------------------------------
    // Query execution
    // -------------------------------------------------------------------------

    /**
     * Execute an Ignition named query.
     *
     * The queryPath must be "ProjectName/path/to/query". The portion before the first
     * "/" is the project name; the rest is the query resource path within that project.
     * When readOnly is true the query runs on a read-only connection.
     */
    private static Object executeNamedQuery(RequestContext req, HttpServletResponse res,
                                            GatewayContext context, String queryPath,
                                            JsonObject paramsJson, boolean readOnly) {
        int slash = queryPath.indexOf('/');
        if (slash < 0) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("Named query path must be 'ProjectName/query/path' (missing '/')");
        }

        String projectName = queryPath.substring(0, slash);
        String resourcePath = queryPath.substring(slash + 1);

        // Convert JSON params to Map<String, Object>
        Map<String, Object> params = new HashMap<>();
        for (Map.Entry<String, com.inductiveautomation.ignition.common.gson.JsonElement> entry
                : paramsJson.entrySet()) {
            params.put(entry.getKey(), jsonElementToObject(entry.getValue()));
        }

        List<String> paramNames = new ArrayList<>(params.keySet());
        Collections.sort(paramNames);
        String target = queryPath + " [" + String.join(",", paramNames) + "]";

        String outcome = "error";
        try {
            Optional<RuntimeResourceCollection> project = context.getProjectManager().find(projectName);
            if (project.isEmpty()) {
                outcome = "rejected";
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return AuthUtil.errorJson("unknown project");
            }
            if (resourcePath.isBlank()
                    || project.get().getResource(new ResourcePath(NamedQuery.RESOURCE_TYPE, resourcePath)).isEmpty()) {
                outcome = "rejected";
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return AuthUtil.errorJson("unknown named query");
            }

            NamedQueryManager nqm = context.getNamedQueryManager();
            Object queryResult = nqm.execute(projectName, resourcePath, params,
                    /*forgiving=*/ false, readOnly, /*txId=*/ null, /*batch=*/ false);
            outcome = readOnly ? "executed-readonly" : "executed";

            if (queryResult instanceof Dataset) {
                return datasetToJson((Dataset) queryResult, queryPath);
            } else {
                // DML: Integer rows affected
                JsonObject result = new JsonObject();
                result.addProperty("query", queryPath);
                result.addProperty("rowsAffected", queryResult instanceof Number
                        ? ((Number) queryResult).intValue() : 0);
                return result;
            }
        } catch (Exception e) {
            log.warn("Named query failed: " + projectName + "/" + resourcePath, e);
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AuthUtil.errorJson("named query failed");
        } finally {
            String actor = AuthUtil.getActorIdentity(req, context);
            String auditProfile = AuditUtil.getAuditProfileForRequest(req, context);
            AuditUtil.audit(context, auditProfile, "Named Query", target, outcome, actor, req);
        }
    }

    // -------------------------------------------------------------------------
    // Serialization helpers
    // -------------------------------------------------------------------------

    /** Convert an Ignition Dataset to a JSON object with columns + rows arrays. */
    private static JsonObject datasetToJson(Dataset ds, String queryPath) {
        int colCount = ds.getColumnCount();

        JsonArray columns = new JsonArray();
        for (int i = 0; i < colCount; i++) {
            JsonObject col = new JsonObject();
            col.addProperty("name", ds.getColumnName(i));
            col.addProperty("type", ds.getColumnType(i).getSimpleName());
            columns.add(col);
        }

        JsonArray rows = new JsonArray();
        int rowCount = ds.getRowCount();
        boolean truncated = rowCount > MAX_ROWS;
        int limit = Math.min(rowCount, MAX_ROWS);
        for (int r = 0; r < limit; r++) {
            JsonArray row = new JsonArray();
            for (int c = 0; c < colCount; c++) {
                row.add(cellToJson(ds.getValueAt(r, c)));
            }
            rows.add(row);
        }

        JsonObject result = new JsonObject();
        result.addProperty("query", queryPath);
        result.add("columns", columns);
        result.add("rows", rows);
        result.addProperty("rowCount", limit);
        if (truncated) {
            result.addProperty("truncated", true);
            result.addProperty("truncatedAt", MAX_ROWS);
        }
        return result;
    }

    /** Serialize a single DB cell value to a JSON element. */
    private static JsonElement cellToJson(Object val) {
        if (val == null) return JsonNull.INSTANCE;
        if (val instanceof Number)  return new JsonPrimitive((Number) val);
        if (val instanceof Boolean) return new JsonPrimitive((Boolean) val);
        if (val instanceof java.sql.Date) {
            return new JsonPrimitive(((java.sql.Date) val).toLocalDate().toString());
        }
        if (val instanceof java.sql.Time) {
            return new JsonPrimitive(((java.sql.Time) val).toLocalTime().toString());
        }
        if (val instanceof java.sql.Timestamp) {
            return new JsonPrimitive(((java.sql.Timestamp) val).toInstant().toString());
        }
        if (val instanceof java.util.Date) {
            return new JsonPrimitive(((java.util.Date) val).toInstant().toString());
        }
        return new JsonPrimitive(val.toString());
    }

    /** Convert a JSON element to a plain Java object for named-query params. */
    private static Object jsonElementToObject(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isBoolean()) return prim.getAsBoolean();
            if (prim.isNumber())  return prim.getAsDouble();
            return prim.getAsString();
        }
        return el.toString();
    }

}
