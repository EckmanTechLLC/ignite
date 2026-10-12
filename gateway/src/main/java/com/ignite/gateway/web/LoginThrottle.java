package com.ignite.gateway.web;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory failed-login throttle keyed by (username, remote address).
 * A key is locked once it reaches maxFailures; the lock lasts lockoutSeconds from the last failure.
 */
public class LoginThrottle {

    private static final class Attempt {
        int failures;
        long lastFailureMs;
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private volatile long lockoutMs = 300_000L;

    public static String key(String username, String remoteAddr) {
        return username + "|" + remoteAddr;
    }

    public boolean isLocked(String key, int maxFailures, int lockoutSeconds) {
        lockoutMs = lockoutSeconds * 1000L;
        Attempt a = attempts.get(key);
        if (a == null) return false;
        synchronized (a) {
            if (System.currentTimeMillis() - a.lastFailureMs >= lockoutMs) {
                attempts.remove(key, a);
                return false;
            }
            return a.failures >= maxFailures;
        }
    }

    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        Attempt a = attempts.computeIfAbsent(key, k -> new Attempt());
        synchronized (a) {
            if (now - a.lastFailureMs >= lockoutMs) {
                a.failures = 0;
            }
            a.failures++;
            a.lastFailureMs = now;
        }
    }

    public void clear(String key) {
        attempts.remove(key);
    }

    /** Drops entries older than the lockout window. */
    public void cleanup() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> now - e.getValue().lastFailureMs >= lockoutMs);
    }
}
