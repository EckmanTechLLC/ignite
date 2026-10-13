package com.ignite.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TagScopeTest {

    @Test
    void blankIsUnrestricted() {
        assertTrue(TagScope.of(null).allows("[default]Anything/At/All"));
        assertTrue(TagScope.of("  \n ").allows("[other]X"));
        assertTrue(TagScope.of("").isUnrestricted());
    }

    @Test
    void exactMatchOnly() {
        TagScope s = TagScope.of("[default]Launch/Clock/Phase");
        assertTrue(s.allows("[default]Launch/Clock/Phase"));
        assertFalse(s.allows("[default]Launch/Clock/Phase2"));
        assertFalse(s.allows("[default]Launch/Clock"));
        assertFalse(s.allows("[default]Launch/Clock/Phase/Sub"));
    }

    @Test
    void singleStarIsDirectChildrenOnly() {
        TagScope s = TagScope.of("[default]Launch/Clock/*");
        assertTrue(s.allows("[default]Launch/Clock/Phase"));
        assertFalse(s.allows("[default]Launch/Clock"));
        assertFalse(s.allows("[default]Launch/Clock/Sub/Deep"));
    }

    @Test
    void doubleStarIsFolderAndEverythingBelow() {
        TagScope s = TagScope.of("[default]Launch/**");
        assertTrue(s.allows("[default]Launch"));
        assertTrue(s.allows("[default]Launch/Clock/Phase"));
        assertFalse(s.allows("[default]LaunchPad/X"));
        assertFalse(s.allows("[default]DC/Hall/PUE"));
    }

    @Test
    void providerIsCaseInsensitiveSegmentsAreExact() {
        TagScope s = TagScope.of("[Default]Launch/**");
        assertTrue(s.allows("[default]Launch/X"));
        assertTrue(s.allows("[DEFAULT]Launch/X"));
        assertFalse(s.allows("[default]launch/X"));
        assertFalse(s.allows("[other]Launch/X"));
    }

    @Test
    void multiplePatternsByLineAndComma() {
        TagScope s = TagScope.of("[default]A/**\n[default]B/x, [default]C/*");
        assertTrue(s.allows("[default]A/1/2"));
        assertTrue(s.allows("[default]B/x"));
        assertTrue(s.allows("[default]C/y"));
        assertFalse(s.allows("[default]B/y"));
    }

    @Test
    void malformedPatternsAreDetectedAndDeny() {
        assertEquals("Launch/**", TagScope.firstInvalid("[default]OK/**\nLaunch/**"));
        assertEquals("[default]A/*/B", TagScope.firstInvalid("[default]A/*/B"));
        assertEquals("[default", TagScope.firstInvalid("[default"));
        assertNull(TagScope.firstInvalid("[default]A/**\n\n[x]y"));
        TagScope s = TagScope.of("nonsense");
        assertFalse(s.isUnrestricted());
        assertFalse(s.allows("[default]Launch/X"));
    }

    @Test
    void traversalAndMalformedPathsAreDenied() {
        TagScope s = TagScope.of("[default]Launch/**");
        assertFalse(s.allows("[default]Launch/../DC/x"));
        assertFalse(s.allows("Launch/X"));
        assertFalse(s.allows((String) null));
    }

    @Test
    void folderBrowseAllowsAncestorsOfScope() {
        TagScope s = TagScope.of("[default]Launch/Clock/**");
        assertTrue(s.allowsFolderBrowse("[default]"));
        assertTrue(s.allowsFolderBrowse("[default]Launch"));
        assertTrue(s.allowsFolderBrowse("[default]Launch/Clock/Sub"));
        assertFalse(s.allowsFolderBrowse("[default]DC"));
        assertFalse(s.allowsFolderBrowse("[other]Launch"));
        assertTrue(TagScope.UNRESTRICTED.allowsFolderBrowse("[any]Thing"));
    }

    @Test
    void alarmSourceConvertsToTagPathForm() {
        assertEquals("[default]Launch/Clock/Phase",
                TagScope.alarmSourceToTagPath("prov:default:/tag:Launch/Clock/Phase"));
        assertEquals("[default]DC/Hall/PUE",
                TagScope.alarmSourceToTagPath("prov:default:/tag:DC/Hall/PUE:/alm:High"));
        assertNull(TagScope.alarmSourceToTagPath("prov:default:/sys:thing"));
        assertNull(TagScope.alarmSourceToTagPath("something:else"));
        assertNull(TagScope.alarmSourceToTagPath(null));
    }

    @Test
    void alarmSourcesAreMatchedAfterConversion() {
        TagScope s = TagScope.of("[default]DC/**");
        assertTrue(s.allowsAlarmSource("prov:default:/tag:DC/Hall/PUE"));
        assertFalse(s.allowsAlarmSource("prov:default:/tag:Launch/Clock/Phase"));
        assertFalse(s.allowsAlarmSource("prov:default:/sys:x"));
        assertTrue(TagScope.UNRESTRICTED.allowsAlarmSource("anything"));
    }
}
