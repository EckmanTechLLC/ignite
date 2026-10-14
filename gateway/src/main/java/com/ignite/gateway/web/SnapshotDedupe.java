package com.ignite.gateway.web;

import com.inductiveautomation.ignition.common.model.values.QualifiedValue;

import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suppresses the subscription's initial per-tag callback, which repeats the snapshot already sent.
 * The first event per path is dropped when it is not newer than the snapshot value and carries the
 * same value and quality; a genuine change (newer timestamp or different value) always passes.
 */
final class SnapshotDedupe {

    private final Map<String, QualifiedValue> snapshot = new ConcurrentHashMap<>();

    /** Records the snapshot value sent for a path (call before subscribing). */
    void record(String path, QualifiedValue qv) {
        if (qv != null) {
            snapshot.put(path, qv);
        }
    }

    /** True when this event is the initial duplicate of the snapshot; only the first event per path is considered. */
    boolean shouldDrop(String path, QualifiedValue qv) {
        QualifiedValue snap = snapshot.remove(path);
        if (snap == null || qv == null) {
            return false;
        }
        Date ts = qv.getTimestamp();
        Date snapTs = snap.getTimestamp();
        if (ts != null && snapTs != null && ts.after(snapTs)) {
            return false;
        }
        String q = qv.getQuality() != null ? qv.getQuality().getName() : null;
        String snapQ = snap.getQuality() != null ? snap.getQuality().getName() : null;
        return Objects.deepEquals(qv.getValue(), snap.getValue()) && Objects.equals(q, snapQ);
    }
}
