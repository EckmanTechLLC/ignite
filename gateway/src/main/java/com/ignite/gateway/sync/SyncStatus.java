package com.ignite.gateway.sync;

import com.inductiveautomation.ignition.common.gson.JsonObject;

import java.time.Instant;

/**
 * Tracks the sync state for a single site.
 *
 * Thread-safe via synchronized setters — updated by the scheduler thread,
 * read by HTTP handler threads.
 */
public class SyncStatus {

    public enum State { PENDING, SYNCING, OK, ERROR }

    private volatile State state = State.PENDING;
    private volatile String commitHash;
    private volatile Instant lastSyncTime;
    private volatile String error;

    // -------------------------------------------------------------------------
    // Transitions
    // -------------------------------------------------------------------------

    public synchronized void setSyncing() {
        this.state = State.SYNCING;
        this.error = null;
    }

    public synchronized void setOk(String commitHash) {
        this.state = State.OK;
        this.commitHash = commitHash;
        this.lastSyncTime = Instant.now();
        this.error = null;
    }

    public synchronized void setError(String error) {
        this.state = State.ERROR;
        this.error = error;
        this.lastSyncTime = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public State getState() {
        return state;
    }

    public String getCommitHash() {
        return commitHash;
    }

    public Instant getLastSyncTime() {
        return lastSyncTime;
    }

    public String getError() {
        return error;
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("state", state.name());
        obj.addProperty("commitHash", commitHash);
        obj.addProperty("lastSyncTime", lastSyncTime != null ? lastSyncTime.toString() : null);
        obj.addProperty("error", error);
        return obj;
    }
}
