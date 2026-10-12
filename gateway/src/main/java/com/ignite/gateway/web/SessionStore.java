package com.ignite.gateway.web;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * In-memory token store for authenticated sessions.
 * Maps token → session info (username, roles, expiry).
 */
public class SessionStore {

    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private static final long SESSION_LIFETIME_MS = 8 * 60 * 60 * 1000; // 8 hours
    private static final long CLEANUP_INTERVAL_MINUTES = 10;
    private ScheduledFuture<?> cleanupTask;

    public static class SessionInfo {
        public final String username;
        public final List<String> roles;
        public final long expiresAt;

        public SessionInfo(String username, List<String> roles) {
            this.username = username;
            this.roles = roles;
            this.expiresAt = System.currentTimeMillis() + SESSION_LIFETIME_MS;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public String createSession(String username, List<String> roles) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new SessionInfo(username, roles));
        cleanup();
        return token;
    }

    public SessionInfo getSession(String token) {
        if (token == null) return null;
        SessionInfo info = sessions.get(token);
        if (info == null) return null;
        if (info.isExpired()) {
            sessions.remove(token);
            return null;
        }
        return info;
    }

    public void invalidate(String token) {
        if (token != null) sessions.remove(token);
    }

    public void cleanup() {
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    /** Schedules cleanup() every 10 minutes; onTick (may be null) runs on the same tick. */
    public synchronized void start(ScheduledExecutorService executor, Runnable onTick) {
        stop();
        cleanupTask = executor.scheduleWithFixedDelay(() -> {
            cleanup();
            if (onTick != null) onTick.run();
        }, CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    public synchronized void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
        }
    }
}
