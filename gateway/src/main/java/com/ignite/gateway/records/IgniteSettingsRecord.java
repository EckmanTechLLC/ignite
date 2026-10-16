package com.ignite.gateway.records;

import com.inductiveautomation.ignition.gateway.localdb.persistence.IdentityField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.IntField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistentRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.RecordMeta;
import com.inductiveautomation.ignition.gateway.localdb.persistence.StringField;

/**
 * Singleton persistent record (id 0) holding gateway-wide Ignite settings (ADR-004 section 2).
 *
 * Use {@code SettingsUtil.get(context)} to obtain the row; it is created on first access.
 */
public class IgniteSettingsRecord extends PersistentRecord {

    public static final RecordMeta<IgniteSettingsRecord> META = new RecordMeta<>(
            IgniteSettingsRecord.class, "IgniteSettings"
    ).setNounKey("IgniteSettingsRecord.Noun").setNounPluralKey("IgniteSettingsRecord.Noun.Plural");

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Primary key. Always 0 — this record is a singleton. */
    public static final IdentityField Id = new IdentityField(META);

    /**
     * Security level required for gateway-wide admin operations (site CRUD, /system/*, /audit/logs).
     * Blank = deny all (not "allow all").
     */
    public static final StringField AdminSecurityLevel = new StringField(META, "AdminSecurityLevel");

    /** Fallback audit profile when the resolved site has none or no site resolves. */
    public static final StringField DefaultAuditProfile = new StringField(META, "DefaultAuditProfile");

    /** The only user source POST /auth/login authenticates against. Default "Ignite-Users". */
    public static final StringField LoginAuthProfile = new StringField(META, "LoginAuthProfile")
            .setDefault("Ignite-Users");

    /** Login failures per (username, remote IP) before lockout. Default 5. */
    public static final IntField LoginMaxFailures = new IntField(META, "LoginMaxFailures")
            .setDefault(5);

    /** Login lockout duration in seconds. Default 300. */
    public static final IntField LoginLockoutSeconds = new IntField(META, "LoginLockoutSeconds")
            .setDefault(300);

    /** Maximum tags per SSE subscription after pattern expansion. Default 1000, range 1-50000. */
    public static final IntField MaxSubscriptionTags = new IntField(META, "MaxSubscriptionTags")
            .setDefault(1000);

    /** Maximum concurrent SSE streams. Default 64, range 1-1000 (raising it past the mount-time value needs a module restart). */
    public static final IntField MaxSseClients = new IntField(META, "MaxSseClients")
            .setDefault(64);

    // -------------------------------------------------------------------------
    // RecordMeta contract
    // -------------------------------------------------------------------------

    @Override
    public RecordMeta<?> getMeta() {
        return META;
    }

    // -------------------------------------------------------------------------
    // Typed getters
    // -------------------------------------------------------------------------

    public Long getId() {
        return getLongObj(Id);
    }

    public String getAdminSecurityLevel() {
        return getString(AdminSecurityLevel);
    }

    public String getDefaultAuditProfile() {
        return getString(DefaultAuditProfile);
    }

    public String getLoginAuthProfile() {
        return getString(LoginAuthProfile);
    }

    public Integer getLoginMaxFailures() {
        return getInt(LoginMaxFailures);
    }

    public Integer getLoginLockoutSeconds() {
        return getInt(LoginLockoutSeconds);
    }

    public Integer getMaxSubscriptionTags() {
        return getInt(MaxSubscriptionTags);
    }

    public Integer getMaxSseClients() {
        return getInt(MaxSseClients);
    }

    // -------------------------------------------------------------------------
    // Typed setters
    // -------------------------------------------------------------------------

    public void setAdminSecurityLevel(String value) {
        setString(AdminSecurityLevel, value);
    }

    public void setDefaultAuditProfile(String value) {
        setString(DefaultAuditProfile, value);
    }

    public void setLoginAuthProfile(String value) {
        setString(LoginAuthProfile, value);
    }

    public void setLoginMaxFailures(Integer value) {
        setInt(LoginMaxFailures, value);
    }

    public void setLoginLockoutSeconds(Integer value) {
        setInt(LoginLockoutSeconds, value);
    }

    public void setMaxSubscriptionTags(Integer value) {
        setInt(MaxSubscriptionTags, value);
    }

    public void setMaxSseClients(Integer value) {
        setInt(MaxSseClients, value);
    }
}
