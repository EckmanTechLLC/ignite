package com.ignite.gateway.sync;

import com.ignite.gateway.records.SiteRecord;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceInterface;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import simpleorm.dataset.SQuery;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Background service that clones and polls Git repositories for all enabled sites.
 *
 * Each enabled site gets a ScheduledFuture that fires at its configured
 * pollIntervalMinutes.  On first run (no .git dir) the repo is cloned; on
 * subsequent runs it is pulled.  The PAT (if any) is passed to git only via the child process environment.
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

    /** Signature of the settings each scheduled task was created with (for idempotent reschedule). */
    private final Map<Long, String> scheduledSigs = new ConcurrentHashMap<>();

    /** Site names by ID, so a delete event (which carries only the key) can find the directory. */
    private final Map<Long, String> siteNames = new ConcurrentHashMap<>();

    /** Sites with a sync currently running or queued: one git process per site directory. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private static final long CLONE_TIMEOUT_S = 120;
    private static final long PULL_TIMEOUT_S = 60;
    private static final long QUICK_TIMEOUT_S = 15;

    /** Shell credential helper reading the PAT from the process environment (nothing on disk). */
    private static final String CREDENTIAL_HELPER =
            "credential.helper=!f() { echo username=x-access-token; echo \"password=$IGNITE_GIT_PAT\"; }; f";

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
            rememberNames(sites);
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
     * Idempotent entry point for every save path (REST and the record listener):
     * schedules an enabled site, cancels a disabled one. If the site is already
     * scheduled with identical settings nothing happens, so the REST handler and the
     * record listener firing for the same save never double-schedule.
     */
    public void reschedule(SiteRecord site) {
        long siteId = site.getId();
        siteNames.put(siteId, site.getName());
        if (!site.isEnabled()) {
            if (scheduledTasks.containsKey(siteId)) {
                log.info("Reschedule: site '" + site.getName() + "' disabled, polling cancelled.");
            }
            cancelSite(siteId);
            return;
        }
        String sig = signature(site);
        if (scheduledTasks.containsKey(siteId) && sig.equals(scheduledSigs.get(siteId))) {
            log.debug("Reschedule: site '" + site.getName() + "' unchanged, nothing to do.");
            return;
        }
        log.info("Reschedule: site '" + site.getName() + "' settings changed.");
        scheduleSite(site);
    }

    private static String signature(SiteRecord site) {
        return String.join("\u0000", String.valueOf(site.getName()), String.valueOf(site.getRepoUrl()),
                String.valueOf(site.getBranch()), String.valueOf(site.getPat()),
                String.valueOf(site.getPollIntervalMinutes()));
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

        siteNames.put(siteId, name);
        scheduledSigs.put(siteId, signature(site));
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> {
                    if (inFlight.add(siteId)) {
                        syncSite(siteId, name, repoUrl, branch, pat);
                    } else {
                        log.debug("Poll skipped for '" + name + "': sync already in progress.");
                    }
                },
                0, intervalMinutes, TimeUnit.MINUTES);

        scheduledTasks.put(siteId, future);
        log.info("Scheduled sync for site '" + name + "' every " + intervalMinutes + "m.");
    }

    /** Cancel polling for a site (called on delete or disable). */
    public void cancelSite(long siteId) {
        scheduledSigs.remove(siteId);
        ScheduledFuture<?> existing = scheduledTasks.remove(siteId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    /**
     * Full cleanup for a deleted site: cancel polling, drop its status entry and remove
     * its synced directory (only if it is strictly inside the sites root and matches the
     * sanitized site name). Idempotent; failures are logged, never thrown.
     *
     * @param name site name, or null to use the name remembered from scheduling
     */
    public void removeSite(long siteId, String name) {
        cancelSite(siteId);
        statusMap.remove(siteId);
        String remembered = siteNames.remove(siteId);
        String effective = name != null ? name : remembered;
        if (effective == null) {
            return;
        }
        try {
            File dir = new File(baseSitesDir, sanitizeName(effective));
            if (!dir.exists()) {
                return;
            }
            if (!isSafeSiteDir(baseSitesDir, dir, effective)) {
                log.warn("Refusing to remove synced directory for deleted site '" + effective
                        + "': path failed containment check.");
                return;
            }
            deleteDir(dir);
            log.info("Removed synced directory for deleted site '" + effective + "'.");
        } catch (Exception e) {
            log.warn("Could not remove synced directory for deleted site '" + effective + "': "
                    + e.getMessage());
        }
    }

    /** Remember names of all sites (including disabled) so a delete event can find the dir. */
    private void rememberNames(List<SiteRecord> sites) {
        for (SiteRecord s : sites) {
            if (s.getId() != null && s.getName() != null) siteNames.put(s.getId(), s.getName());
        }
    }

    /**
     * Trigger an immediate (one-shot) sync for a site by ID.
     *
     * @return null if queued, otherwise a short reason why it was not
     */
    public String triggerSync(long siteId) {
        try {
            SiteRecord site = context.getLocalPersistenceInterface().find(SiteRecord.META, siteId);
            if (site == null) return "site not found";
            if (!inFlight.add(siteId)) return "sync in progress";
            final String name = site.getName();
            final String repoUrl = site.getRepoUrl();
            final String branch = site.getBranch();
            final String pat = site.getPat();
            try {
                scheduler.submit(() -> syncSite(siteId, name, repoUrl, branch, pat));
            } catch (RuntimeException e) {
                inFlight.remove(siteId);
                throw e;
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to submit immediate sync for site " + siteId, e);
            return "failed to queue sync";
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
        // Caller has reserved this site in inFlight; always release it.
        try {
            log.debug("Syncing site: " + name);

            SyncStatus status = statusMap.computeIfAbsent(siteId, k -> new SyncStatus());
            status.setSyncing();

            File siteDir = new File(baseSitesDir, sanitizeName(name));

            try {
                if (repoUrl == null || repoUrl.isBlank()) {
                    throw new IllegalArgumentException("Repository URL is not configured.");
                }

                if (new File(siteDir, ".git").isDirectory()) {
                    pull(siteDir, repoUrl, branch, pat, status);
                } else {
                    clone(siteDir, repoUrl, branch, pat, status);
                }

                log.info("Site '" + name + "' synced. Commit: " + status.getCommitHash());
            } catch (Exception e) {
                String msg = e.getMessage();
                log.warn("Sync failed for site '" + name + "': " + msg);
                status.setError(msg);
            }
        } finally {
            inFlight.remove(siteId);
        }
    }

    private void clone(File siteDir, String repoUrl, String branch, String pat, SyncStatus status)
            throws Exception {
        // Ensure parent exists but not the target dir (git clone creates it)
        siteDir.getParentFile().mkdirs();
        if (siteDir.exists()) {
            deleteDir(siteDir);
        }

        // Clean URL on the command line; the PAT reaches git only via the process environment
        run(new String[]{
                "git", "clone",
                "--branch", branch,
                "--single-branch",
                "--depth", "1",
                repoUrl,
                siteDir.getAbsolutePath()
        }, null, pat, CLONE_TIMEOUT_S);

        status.setOk(readHeadCommit(siteDir, pat));
    }

    private void pull(File siteDir, String repoUrl, String branch, String pat, SyncStatus status)
            throws Exception {
        // Keep origin on the clean URL (also scrubs any token an older build stored here)
        run(new String[]{"git", "remote", "set-url", "origin", repoUrl}, siteDir, pat, QUICK_TIMEOUT_S);
        run(new String[]{"git", "pull", "origin", branch}, siteDir, pat, PULL_TIMEOUT_S);
        status.setOk(readHeadCommit(siteDir, pat));
    }

    /**
     * Runs git with a hard timeout and returns its combined output. When a PAT is given it
     * is supplied through an inline credential helper that reads it from the child's
     * environment, so no secret is written to .git/config or placed on the command line.
     * On timeout the process tree is killed and a generic error is thrown.
     */
    private String run(String[] gitCmd, File workDir, String pat, long timeoutSeconds)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(gitCmd[0]);
        boolean hasPat = pat != null && !pat.isBlank();
        if (hasPat) {
            cmd.add("-c");
            cmd.add("credential.helper=");
            cmd.add("-c");
            cmd.add(CREDENTIAL_HELPER);
        }
        for (int i = 1; i < gitCmd.length; i++) cmd.add(gitCmd[i]);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (workDir != null) {
            pb.directory(workDir);
        }
        // Prevent git from prompting for credentials (would hang)
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "echo");
        if (hasPat) {
            pb.environment().put("IGNITE_GIT_PAT", pat.trim());
        }

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (output) { output.append(line).append("\n"); }
                }
            } catch (IOException ignored) {
                // stream closed by destroy
            }
        }, "ignite-git-output");
        reader.setDaemon(true);
        reader.start();

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new RuntimeException("git operation timed out");
        }
        reader.join(2000);

        String text;
        synchronized (output) { text = output.toString().trim(); }
        if (process.exitValue() != 0) {
            throw new RuntimeException(redact(text, pat));
        }
        return text;
    }

    private String readHeadCommit(File siteDir, String pat) {
        try {
            String line = run(new String[]{"git", "rev-parse", "HEAD"}, siteDir, pat, QUICK_TIMEOUT_S);
            return line.isEmpty() ? "unknown" : line;
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
            run(new String[]{"git", "ls-remote", "--heads", repoUrl, "refs/heads/" + branch},
                    null, pat, QUICK_TIMEOUT_S);
            return null; // success
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers (package-visible for testing)
    // -------------------------------------------------------------------------

    /** Replace any embedded token in a URL with *** for safe logging / error messages. */
    static String redactUrl(String text) {
        if (text == null) return null;
        return text.replaceAll("https://[^@\\s]+@", "https://***@");
    }

    /** Redact URL-embedded tokens and the literal PAT from text. */
    static String redact(String text, String pat) {
        String out = redactUrl(text);
        if (out != null && pat != null && !pat.isBlank()) {
            out = out.replace(pat.trim(), "***");
        }
        return out;
    }

    /**
     * True only if {@code dir} canonically lies strictly inside {@code root} as a direct
     * child whose name equals the sanitized site name.
     */
    static boolean isSafeSiteDir(File root, File dir, String siteName) {
        try {
            if (siteName == null || siteName.isBlank()) return false;
            java.nio.file.Path r = root.getCanonicalFile().toPath();
            java.nio.file.Path d = dir.getCanonicalFile().toPath();
            return d.startsWith(r) && !d.equals(r) && r.equals(d.getParent())
                    && d.getFileName().toString().equals(sanitizeName(siteName));
        } catch (IOException e) {
            return false;
        }
    }

    /** Convert a site name to a safe filesystem directory name. */
    static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
