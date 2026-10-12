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
            db.save(settings);
            log.info("Created Ignite settings record with defaults.");
        }
        return settings;
    }

    /** Ensures the settings row exists (call at startup). */
    public static void ensureExists(GatewayContext context) {
        get(context);
    }
}
