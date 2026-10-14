package com.ignite.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class StaticFileRoutesTest {

    @Test
    void escapesScriptBreakingCharacters() {
        String out = StaticFileRoutes.escapeJsonForScript("{\"a\":\"</script><b>&\u2028\u2029\"}");
        assertFalse(out.contains("<"));
        assertFalse(out.contains(">"));
        assertFalse(out.contains("&"));
        assertFalse(out.contains("\u2028"));
        assertFalse(out.contains("\u2029"));
        assertEquals("{\"a\":\"\\u003c/script\\u003e\\u003cb\\u003e\\u0026\\u2028\\u2029\"}", out);
    }

    @Test
    void envHashChangesWithContent() {
        assertNotEquals(StaticFileRoutes.envHash("{\"k\":\"a\"}"), StaticFileRoutes.envHash("{\"k\":\"b\"}"));
        assertEquals(StaticFileRoutes.envHash("{}"), StaticFileRoutes.envHash("{}"));
    }
}
