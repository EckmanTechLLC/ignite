package com.ignite.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CorsUtilTest {

    @Test
    void echoesListedOriginCaseInsensitively() {
        String list = "http://a.local:5173, http://B.local";
        assertEquals("http://a.local:5173", CorsUtil.matchOrigin("http://a.local:5173", list));
        assertEquals("http://b.local", CorsUtil.matchOrigin("http://b.local", list));
    }

    @Test
    void rejectsUnlistedOrMissingOrigin() {
        assertNull(CorsUtil.matchOrigin("http://evil.local", "http://a.local"));
        assertNull(CorsUtil.matchOrigin("http://a.local.evil", "http://a.local"));
        assertNull(CorsUtil.matchOrigin(null, "http://a.local"));
        assertNull(CorsUtil.matchOrigin("http://a.local", null));
        assertNull(CorsUtil.matchOrigin("http://a.local", "  "));
    }

    @Test
    void wildcardMatchesAnyOrigin() {
        assertEquals("*", CorsUtil.matchOrigin("http://x.local", " * "));
    }

    @Test
    void preflightHeaderAdvertisesServerKeyHeader() {
        assertEquals(true, CorsUtil.ALLOW_HEADERS.contains("X-Ignite-Key"));
        assertEquals(false, CorsUtil.ALLOW_HEADERS.contains("X-Ignite-API" + "-Key"));
    }
}
