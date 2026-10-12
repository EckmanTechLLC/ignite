package com.ignite.gateway.web;

import com.ignite.gateway.SettingsUtil;
import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.model.ApplicationScope;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.audit.AuditProfile;
import com.inductiveautomation.ignition.common.audit.AuditRecord;
import com.inductiveautomation.ignition.gateway.audit.AuditRecordBuilder;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Utility for writing audit records to Ignition's built-in audit system.
 *
 * Usage:
 *   String auditProfile = AuditUtil.getAuditProfileForRequest(req, context);
 *   AuditUtil.audit(context, auditProfile, "Tag Write", tagPath, value, actor, req);
 *
 * Silently skips if no audit profile is configured or the profile is not found.
 */
public final class AuditUtil {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.AuditUtil");

    private static final AtomicBoolean warnedNoProfile = new AtomicBoolean(false);

    private AuditUtil() {}

    /**
     * Write an audit record to the named Ignition audit profile.
     *
     * Silently skips if auditProfileName is null/blank or the profile is not found.
     * Logs a warning but does NOT throw or fail the request on any error.
     *
     * @param context          gateway context
     * @param auditProfileName name of the Ignition audit profile
     * @param action           e.g. "Tag Write", "Alarm Acknowledge", "Site Create"
     * @param target           e.g. tag path, alarm IDs, site name
     * @param value            e.g. new value, "acknowledged", "created"
     * @param actor            e.g. "admin" or "api-key:demo"
     * @param req              incoming request (for client IP)
     */
    public static void audit(
            GatewayContext context,
            String auditProfileName,
            String action,
            String target,
            String value,
            String actor,
            RequestContext req) {

        if (auditProfileName == null || auditProfileName.isBlank()) {
            return;
        }

        try {
            AuditProfile profile = context.getAuditManager().getProfile(auditProfileName);
            if (profile == null) {
                log.warn("Audit profile not found: " + auditProfileName);
                return;
            }

            String actorHost = req.getRequest().getRemoteAddr();

            AuditRecord record = new AuditRecordBuilder()
                    .setAction(action)
                    .setActionTarget(target)
                    .setActionValue(value)
                    .setActor(actor)
                    .setActorHost(actorHost)
                    .setOriginatingContext(ApplicationScope.GATEWAY)
                    .setOriginatingSystem("Ignite")
                    .setStatusCode(0)
                    .setTimestamp(new Date())
                    .build();

            profile.audit(record);
        } catch (Exception e) {
            log.warn("Failed to write audit record", e);
        }
    }

    /**
     * Resolve the audit profile name: the site's profile if set, else the
     * gateway-wide default, else null (logged once at WARN).
     */
    public static String resolveProfile(GatewayContext ctx, SiteRecord site) {
        if (site != null) {
            String name = site.getAuditProfileName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        String def = SettingsUtil.get(ctx).getDefaultAuditProfile();
        if (def != null && !def.isBlank()) {
            return def;
        }
        if (warnedNoProfile.compareAndSet(false, true)) {
            log.warn("No audit profile configured (site or gateway default); Ignite actions are not being audited");
        }
        return null;
    }

    /**
     * Resolve the audit profile name for a request via its SiteRecord (API key
     * or site header/param), falling back to the gateway-wide default.
     */
    public static String getAuditProfileForRequest(RequestContext req, GatewayContext context) {
        return resolveProfile(context, AuthUtil.getSiteForRequest(req, context));
    }
}
