package com.ignite.gateway.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inductiveautomation.ignition.common.model.values.BasicQualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import java.util.Date;
import org.junit.jupiter.api.Test;

class SnapshotDedupeTest {

    private static final String P = "[default]MG/_Sim_/Seed";

    private static BasicQualifiedValue qv(Object v, long ts) {
        return new BasicQualifiedValue(v, QualityCode.Good, new Date(ts));
    }

    @Test
    void dropsIdenticalInitialEventOnce() {
        SnapshotDedupe d = new SnapshotDedupe();
        d.record(P, qv(42, 1000));
        assertTrue(d.shouldDrop(P, qv(42, 1000)));
        assertFalse(d.shouldDrop(P, qv(42, 1000)));
    }

    @Test
    void passesNewerTimestamp() {
        SnapshotDedupe d = new SnapshotDedupe();
        d.record(P, qv(42, 1000));
        assertFalse(d.shouldDrop(P, qv(42, 2000)));
    }

    @Test
    void passesChangedValue() {
        SnapshotDedupe d = new SnapshotDedupe();
        d.record(P, qv(42, 1000));
        assertFalse(d.shouldDrop(P, qv(43, 1000)));
    }

    @Test
    void passesWhenNoSnapshot() {
        assertFalse(new SnapshotDedupe().shouldDrop(P, qv(1, 1000)));
    }

    @Test
    void passesQualityChange() {
        SnapshotDedupe d = new SnapshotDedupe();
        d.record(P, qv(42, 1000));
        assertFalse(d.shouldDrop(P, new BasicQualifiedValue(42, QualityCode.Bad, new Date(1000))));
    }
}
