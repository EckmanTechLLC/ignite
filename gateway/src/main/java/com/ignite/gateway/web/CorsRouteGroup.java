package com.ignite.gateway.web;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.ItemListRouteHandler;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.ResponseRenderer;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.GroupObject;
import com.inductiveautomation.ignition.gateway.dataroutes.OpenApiOperationBuilder;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import jakarta.servlet.http.HttpServletResponse;
import simpleorm.dataset.SQuery;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * RouteGroup decorator that adds CORS to every route mounted through it.
 *
 * The SDK has no filter or wildcard OPTIONS route, but RouteGroup is an interface, so this wrapper
 * (a) wraps each route handler: when the request has an Origin listed in the resolved site's
 * corsOrigins (site from API key, X-Ignite-Site or the site query param), CORS headers are set
 * before the handler runs; and (b) mounts one OPTIONS preflight route per distinct path, which
 * answers 204 with no auth check, emitting headers when the Origin is listed on any site.
 * Origins not listed get no CORS headers (the browser blocks the call).
 */
public final class CorsRouteGroup implements RouteGroup {

    private static final LoggerEx log = LoggerEx.newBuilder().build("ignite.gateway.CorsRouteGroup");

    private final RouteGroup delegate;
    private final GatewayContext context;
    private final Set<String> preflightPaths = new HashSet<>();

    public CorsRouteGroup(RouteGroup delegate, GatewayContext context) {
        this.delegate = delegate;
        this.context = context;
    }

    @Override
    public RouteMounter newRoute(String path) {
        return new CorsMounter(path, delegate.newRoute(path));
    }

    @Override
    public RouteGroup addOpenApiGroup(GroupObject group) {
        delegate.addOpenApiGroup(group);
        return this;
    }

    private void mountPreflight(String path) {
        if (!preflightPaths.add(path)) {
            return;
        }
        delegate.newRoute(path)
                .method(HttpMethod.OPTIONS)
                .type(TYPE_JSON)
                .handler((req, res) -> {
                    String origin = req.getRequest().getHeader("Origin");
                    if (origin != null) {
                        String allow = CorsUtil.matchAnySite(origin, allSites());
                        if (allow != null) {
                            CorsUtil.applyApi(res, allow);
                        }
                    }
                    res.setStatus(HttpServletResponse.SC_NO_CONTENT);
                    return null;
                })
                .accessControl(AccessControlStrategy.OPEN_ROUTE)
                .mount();
    }

    private List<SiteRecord> allSites() {
        try {
            PersistenceInterface db = context.getLocalPersistenceInterface();
            return db.query(new SQuery<>(SiteRecord.META));
        } catch (Exception e) {
            log.warn("CORS site lookup failed: " + e.getMessage());
            return List.of();
        }
    }

    private RouteHandler wrap(RouteHandler inner) {
        return (req, res) -> {
            String origin = req.getRequest().getHeader("Origin");
            if (origin != null && !origin.isBlank()) {
                SiteRecord site = AuthUtil.getSiteForRequest(req, context);
                String allow = site == null ? null : CorsUtil.matchOrigin(origin, site.getCorsOrigins());
                if (allow != null) {
                    CorsUtil.applyApi(res, allow);
                }
            }
            return inner.handle(req, res);
        };
    }

    /** Delegating mounter: wraps the handler and mounts the preflight route after the real one. */
    private final class CorsMounter implements RouteMounter {
        private final String path;
        private final RouteMounter real;
        private HttpMethod method;

        CorsMounter(String path, RouteMounter real) {
            this.path = path;
            this.real = real;
        }

        @Override public RouteMounter method(HttpMethod m) { method = m; real.method(m); return this; }
        @Override public RouteMounter type(String t) { real.type(t); return this; }
        @Override public RouteMounter acceptedTypes(String... t) { real.acceptedTypes(t); return this; }
        @Override public RouteMounter handler(RouteHandler h) { real.handler(wrap(h)); return this; }
        @Override public <T> ListRouteMounter<T> itemListHandler(ItemListRouteHandler<T> h) { return real.itemListHandler(h); }
        @Override public RouteMounter renderer(ResponseRenderer r) { real.renderer(r); return this; }
        @Override public RouteMounter accessControl(AccessControlStrategy a) { real.accessControl(a); return this; }
        @Override public RouteMounter requirePermission(PermissionType p) { real.requirePermission(p); return this; }
        @Override public RouteMounter concurrency(int a, int b) { real.concurrency(a, b); return this; }
        @Override public RouteMounter cache(long d, TimeUnit u, CacheKeyGen k) { real.cache(d, u, k); return this; }
        @Override public RouteMounter nocache() { real.nocache(); return this; }
        @Override public RouteMounter gzip() { real.gzip(); return this; }
        @Override public RouteMounter gzip(boolean g) { real.gzip(g); return this; }
        @Override public RouteMounter openApi(Consumer<OpenApiOperationBuilder> c) { real.openApi(c); return this; }

        @Override
        public void mount() {
            real.mount();
            if (method != HttpMethod.OPTIONS) {
                mountPreflight(path);
            }
        }
    }
}
