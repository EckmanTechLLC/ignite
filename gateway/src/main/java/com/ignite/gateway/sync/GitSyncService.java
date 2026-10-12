package com.ignite.gateway.sync;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import simpleorm.dataset.SQuery;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Background service that clones and polls Git repositories for all enabled sites.
 *
 * Each enabled site gets a ScheduledFuture that fires at its configured
 * pollIntervalMinutes.  On first run (no .git dir) the repo is cloned; on
 * subsequent runs it is pulled.  Auth is injected into the HTTPS URL via PAT.
 *
 * Repos are stored under: <ignition-home>/data/ignite/sites/<site-name>/
 *
 * Started by IgniteGatewayHook.startup() and stopped by shutdown().
 */
public class GitSyncService {

    private static final LoggerEx log = LoggerEx.newBuilder()
            .build("ignite.gateway.GitSyncService");

    private final GatewayContext context;
    private final File baseSitesDir;
    private final ScheduledExecutorService scheduler;

    /** Current sync status per site ID. */
    private final Map<Long, SyncStatus> statusMap = new ConcurrentHashMap<>();

    /** Active scheduled poll tasks per site ID. */
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public GitSyncService(GatewayContext context) {
        this.context = context;
        this.baseSitesDir = new File(context.getSystemManager().getDataDir(), "ignite/sites");
        this.scheduler = Executors.newScheduledThreadPool(2,
                r -> {
                    Thread t = new Thread(r, "ignite-git-sync");
                    t.setDaemon(true);
                    return t;
                });
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void start() {
        log.info("GitSyncService starting. Repos stored at: " + baseSitesDir.getAbsolutePath());
        baseSitesDir.mkdirs();
        scheduleSites();
    }

    public void stop() {
        log.info("GitSyncService stopping.");
        scheduledTasks.values().forEach(f -> f.cancel(false));
        scheduledTasks.clear();
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Scheduling
    // -------------------------------------------------------------------------

    /** Schedule all currently enabled sites. Called on startup. */
    public void scheduleSites() {
        try {
            PersistenceInterface db = context.getLocalPersistenceInterface();
            List<SiteRecord> sites = db.query(new SQuery<>(SiteRecord.META));
            for (SiteRecord site : sites) {
                if (site.isEnabled()) {
                    scheduleSite(site);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load sites for scheduling", e);
        }
    }

    /**
     * Schedule (or re-schedule) a single site.
     * Copies all needed fields out of the record before handing off to background thread.
     */
    public void scheduleSite(SiteRecord site) {
        long siteId = site.getId();
        cancelSite(siteId);  // cancel any existing task first

        int intervalMinutes = site.getPollIntervalMinutes();
        if (intervalMinutes < 1) intervalMinutes = 5;

        // Copy fields into locals — SiteRecord may be evicted from DB session
        final String name = site.getName();
        final String repoUrl = site.getRepoUrl();
        final String branch = site.getBranch();
        final String pat = site.getPat();

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> syncSite(siteId, name, repoUrl, branch, pat),
                0, intervalMinutes, TimeUnit.MINUTES);

        scheduledTasks.put(siteId, future);
        log.info("Scheduled sync for site '" + name + "' every " + intervalMinutes + "m.");
    }

    /** Cancel polling for a site (called on delete or disable). */
    public void cancelSite(long siteId) {
        ScheduledFuture<?> existing = scheduledTasks.remove(siteId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    /** Trigger an immediate (one-shot) sync for a site by ID. */
    public void triggerSync(long siteId) {
        try {
            SiteRecord site = context.getLocalPersistenceInterface().find(SiteRecord.META, siteId);
            if (site == null) return;
            final String name = site.getName();
            final String repoUrl = site.getRepoUrl();
            final String branch = site.getBranch();
            final String pat = site.getPat();
            scheduler.submit(() -> syncSite(siteId, name, repoUrl, branch, pat));
        } catch (Exception e) {
            log.error("Failed to submit immediate sync for site " + siteId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    /** Returns the current sync status for a site. Never null. */
    public SyncStatus getStatus(long siteId) {
        return statusMap.getOrDefault(siteId, new SyncStatus());
    }

    // -------------------------------------------------------------------------
    // Core sync logic
    // -------------------------------------------------------------------------

    private void syncSite(long siteId, String name, String repoUrl, String branch, String pat) {
        log.debug("Syncing site: " + name);

        SyncStatus status = statusMap.computeIfAbsent(siteId, k -> new SyncStatus());
        status.setSyncing();

        File siteDir = new File(baseSitesDir, sanitizeName(name));

        try {
            if (repoUrl == null || repoUrl.isBlank()) {
                throw new IllegalArgumentException("Repository URL is not configured.");
            }
            String authUrl = injectPat(repoUrl, pat);

            if (new File(siteDir, ".git").isDirectory()) {
                pull(siteDir, authUrl, branch, status);
            } else {
                clone(siteDir, authUrl, branch, status);
            }

            log.info("Site '" + name + "' synced. Commit: " + status.getCommitHash());
        } catch (Exception e) {
            String msg = e.getMessage();
            log.warn("Sync failed for site '" + name + "': " + msg);
            status.setError(msg);
        }
    }

    private void clone(File siteDir, String authUrl, String branch, SyncStatus status)
            throws Exception {
        // Ensure parent exists but not the target dir (git clone creates it)
        siteDir.getParentFile().mkdirs();
        if (siteDir.exists()) {
            deleteDir(siteDir);
        }

        runCommand(new String[]{
                "git", "clone",
                "--branch", branch,
                "--single-branch",
                "--depth", "1",
                authUrl,
                siteDir.getAbsolutePath()
        }, null);

        status.setOk(readHeadCommit(siteDir));
    }

    private void pull(File siteDir, String authUrl, String branch, SyncStatus status)
            throws Exception {
        // Update remote URL in case PAT was rotated
        runCommand(new String[]{"git", "remote", "set-url", "origin", authUrl}, siteDir);
        runCommand(new String[]{"git", "pull", "origin", branch}, siteDir);
        status.setOk(readHeadCommit(siteDir));
    }

    private void runCommand(String[] cmd, File workDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir);
        }
        // Prevent git from prompting for credentials (would hang)
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "echo");

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Redact any auth token before throwing / logging
            throw new RuntimeException(redactUrl(output.toString().trim()));
        }
    }

    private String readHeadCommit(File siteDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
            pb.directory(siteDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                p.waitFor();
                return line != null ? line.trim() : "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    // -------------------------------------------------------------------------
    // Git connectivity test (lightweight — no clone)
    // -------------------------------------------------------------------------

    /**
     * Tests git connectivity by running {@code git ls-remote <url> HEAD}.
     * Returns null on success, or an error message string on failure.
     */
    public String testConnectivity(String repoUrl, String pat, String branch) {
        try {
            String authUrl = injectPat(repoUrl, pat);
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "ls-remote", "--heads", authUrl, "refs/heads/" + branch);
            pb.redirectErrorStream(true);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", "echo");

            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                return redactUrl(output.toString().trim());
            }
            return null; // success
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers (package-visible for testing)
    // -------------------------------------------------------------------------

    /** Inject PAT into HTTPS URL: https://host/... → https://<pat>@host/... */
    static String injectPat(String repoUrl, String pat) {
        if (pat == null || pat.isBlank()) return repoUrl;
        if (repoUrl == null) return null;
        if (repoUrl.startsWith("https://")) {
            return "https://" + pat.trim() + "@" + repoUrl.substring("https://".length());
        }
        return repoUrl;
    }

    /** Replace any embedded token in a URL with *** for safe logging / error messages. */
    static String redactUrl(String text) {
        if (text == null) return null;
        return text.replaceAll("https://[^@\\s]+@", "https://***@");
    }

    /** Convert a site name to a safe filesystem directory name. */
    static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
