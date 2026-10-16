package com.ignite.gateway;

import com.ignite.gateway.records.IgniteSettingsRecord;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

/**
 * Accessor for the singleton {@link IgniteSettingsRecord} (id 0).
 */
public final class SettingsUtil {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.SettingsUtil");

    private static final long SETTINGS_ID = 0L;

    public static final int DEFAULT_MAX_SUBSCRIPTION_TAGS = 1000;
    public static final int MAX_MAX_SUBSCRIPTION_TAGS = 50000;
    public static final int DEFAULT_MAX_SSE_CLIENTS = 64;
    public static final int MAX_MAX_SSE_CLIENTS = 1000;

    private SettingsUtil() {}

    /**
     * Returns the settings record, creating it with defaults on first access.
     * Never returns null.
     */
    public static synchronized IgniteSettingsRecord get(GatewayContext context) {
        PersistenceInterface db = context.getLocalPersistenceInterface();
        IgniteSettingsRecord settings = db.find(IgniteSettingsRecord.META, SETTINGS_ID);
        if (settings == null) {
            settings = db.createNew(IgniteSettingsRecord.META);
            settings.setLong(IgniteSettingsRecord.Id, SETTINGS_ID);
            settings.setAdminSecurityLevel("");
            settings.setDefaultAuditProfile("");
            settings.setLoginAuthProfile("Ignite-Users");
            settings.setLoginMaxFailures(5);
            settings.setLoginLockoutSeconds(300);
            settings.setMaxSubscriptionTags(DEFAULT_MAX_SUBSCRIPTION_TAGS);
            settings.setMaxSseClients(DEFAULT_MAX_SSE_CLIENTS);
            db.save(settings);
            log.info("Created Ignite settings record with defaults.");
        }
        return settings;
    }

    /** Configured per-subscription tag cap; the default when unset or out of range. */
    public static int maxSubscriptionTags(GatewayContext context) {
        Integer v = get(context).getMaxSubscriptionTags();
        return v == null || v < 1 || v > MAX_MAX_SUBSCRIPTION_TAGS ? DEFAULT_MAX_SUBSCRIPTION_TAGS : v;
    }

    /** Configured concurrent SSE client cap; the default when unset or out of range. */
    public static int maxSseClients(GatewayContext context) {
        Integer v = get(context).getMaxSseClients();
        return v == null || v < 1 || v > MAX_MAX_SSE_CLIENTS ? DEFAULT_MAX_SSE_CLIENTS : v;
    }

    /** Ensures the settings row exists (call at startup). */
    public static void ensureExists(GatewayContext context) {
        get(context);
    }
}
