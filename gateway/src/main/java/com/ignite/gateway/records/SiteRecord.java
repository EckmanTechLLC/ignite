package com.ignite.gateway.records;

import com.inductiveautomation.ignition.gateway.localdb.persistence.BooleanField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.EncodedStringField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.IdentityField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.IntField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistentRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.RecordMeta;
import com.inductiveautomation.ignition.gateway.localdb.persistence.StringField;
import simpleorm.dataset.SFieldFlags;

/**
 * Persistent record for a managed static site configuration.
 *
 * Stored in the Ignition internal SQLite database. Each instance represents
 * one site: a git-hosted set of pre-built static assets served at a URL path.
 */
public class SiteRecord extends PersistentRecord {

    public static final RecordMeta<SiteRecord> META = new RecordMeta<>(
            SiteRecord.class, "IgniteSite"
    ).setNounKey("SiteRecord.Noun").setNounPluralKey("SiteRecord.Noun.Plural");

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Auto-increment primary key. */
    public static final IdentityField Id = new IdentityField(META);

    /** Human-readable unique identifier used in logs and the config UI. */
    public static final StringField Name = new StringField(META, "Name",
            SFieldFlags.SMANDATORY, SFieldFlags.SDESCRIPTIVE)
            .setUnique(true)
            .setIndexed(true);

    /** URL path segment where this site is served, e.g. "dashboard". */
    public static final StringField MountPath = new StringField(META, "MountPath",
            SFieldFlags.SMANDATORY)
            .setUnique(true)
            .setIndexed(true);

    /** HTTPS git repo URL, e.g. "https://github.com/org/repo". */
    public static final StringField RepoUrl = new StringField(META, "RepoUrl",
            SFieldFlags.SMANDATORY);

    /** Branch to track. Defaults to "main". */
    public static final StringField Branch = new StringField(META, "Branch",
            SFieldFlags.SMANDATORY)
            .setDefault("main");

    /**
     * Personal access token for private repositories — stored encoded.
     * Leave empty for public repos.
     */
    public static final EncodedStringField Pat = new EncodedStringField(META, "Pat");

    /** How often to poll the git remote for changes, in minutes. */
    public static final IntField PollIntervalMinutes = new IntField(META, "PollIntervalMinutes")
            .setDefault(5);

    /**
     * JSON object of environment variable key/value pairs injected into the
     * served site at runtime (e.g. {"API_URL": "https://..."}).
     */
    public static final StringField EnvVars = new StringField(META, "EnvVars", 8192);

    /** Comma-separated allowed CORS origins, e.g. "https://app.example.com". */
    public static final StringField CorsOrigins = new StringField(META, "CorsOrigins");

    /** Per-site API key for external consumers — stored encoded. */
    public static final EncodedStringField ApiKey = new EncodedStringField(META, "ApiKey");

    /**
     * Subdirectory inside the repo to serve. Defaults to "/" (repo root).
     * Use "dist" if the repo has a build output directory.
     */
    public static final StringField ServePath = new StringField(META, "ServePath")
            .setDefault("/");

    /** When false the site is not served and not synced. */
    public static final BooleanField Enabled = new BooleanField(META, "Enabled")
            .setDefault(true);

    /** Ignition security level required for write operations (tag writes, alarm acks, script execution). Empty = any authenticated user. */
    public static final StringField WriteSecurityLevel = new StringField(META, "WriteSecurityLevel");

    /** Ignition security level required for admin operations (site config, system endpoints). Empty = any authenticated user. */
    public static final StringField AdminSecurityLevel = new StringField(META, "AdminSecurityLevel");

    /** Name of the Ignition audit profile to log write operations to. Empty = no auditing. */
    public static final StringField AuditProfileName = new StringField(META, "AuditProfileName");

    /** Tag allow-list patterns (one per line or comma-separated). Blank = unrestricted (ADR-004 A1). */
    public static final StringField TagPathPatterns = new StringField(META, "TagPathPatterns", 4096);

    /** When true, tag writes, alarm acks and library scripts for this site must carry a valid e-signature (ADR-006). */
    public static final BooleanField RequireSignature = new BooleanField(META, "RequireSignature")
            .setDefault(false);

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

    public String getName() {
        return getString(Name);
    }

    public String getMountPath() {
        return getString(MountPath);
    }

    public String getRepoUrl() {
        return getString(RepoUrl);
    }

    public String getBranch() {
        return getString(Branch);
    }

    public String getPat() {
        return getString(Pat);
    }

    public Integer getPollIntervalMinutes() {
        return getIntObj(PollIntervalMinutes);
    }

    public String getEnvVars() {
        return getString(EnvVars);
    }

    public String getCorsOrigins() {
        return getString(CorsOrigins);
    }

    public String getApiKey() {
        return getString(ApiKey);
    }

    public String getServePath() {
        return getString(ServePath);
    }

    public boolean isEnabled() {
        return getBoolean(Enabled);
    }

    public boolean isRequireSignature() {
        return getBoolean(RequireSignature);
    }

    // -------------------------------------------------------------------------
    // Typed setters
    // -------------------------------------------------------------------------

    public void setName(String value) {
        setString(Name, value);
    }

    public void setMountPath(String value) {
        setString(MountPath, value);
    }

    public void setRepoUrl(String value) {
        setString(RepoUrl, value);
    }

    public void setBranch(String value) {
        setString(Branch, value);
    }

    public void setPat(String value) {
        setString(Pat, value);
    }

    public void setPollIntervalMinutes(Integer value) {
        setInt(PollIntervalMinutes, value == null ? 5 : value);
    }

    public void setEnvVars(String value) {
        setString(EnvVars, value);
    }

    public void setCorsOrigins(String value) {
        setString(CorsOrigins, value);
    }

    public void setApiKey(String value) {
        setString(ApiKey, value);
    }

    public void setServePath(String value) {
        setString(ServePath, value);
    }

    public void setEnabled(boolean value) {
        setBoolean(Enabled, value);
    }

    public void setRequireSignature(boolean value) {
        setBoolean(RequireSignature, value);
    }

    public String getWriteSecurityLevel() {
        return getString(WriteSecurityLevel);
    }

    public void setWriteSecurityLevel(String value) {
        setString(WriteSecurityLevel, value);
    }

    public String getAdminSecurityLevel() {
        return getString(AdminSecurityLevel);
    }

    public void setAdminSecurityLevel(String value) {
        setString(AdminSecurityLevel, value);
    }

    public String getAuditProfileName() {
        return getString(AuditProfileName);
    }

    public void setAuditProfileName(String value) {
        setString(AuditProfileName, value);
    }

    public String getTagPathPatterns() {
        return getString(TagPathPatterns);
    }

    public void setTagPathPatterns(String value) {
        setString(TagPathPatterns, value);
    }
}
