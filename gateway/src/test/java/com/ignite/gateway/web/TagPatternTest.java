package com.ignite.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TagPatternTest {

    @Test
    void literalPathMatchesOnlyItself() {
        TagPattern p = TagPattern.parse("[default]CR/Rooms/R1/Status");
        assertNotNull(p);
        assertTrue(p.isLiteral());
        assertEquals("[default]CR/Rooms/R1/Status", p.literalPath());
        assertTrue(p.matches("[default]CR/Rooms/R1/Status"));
        assertFalse(p.matches("[default]CR/Rooms/R2/Status"));
        assertFalse(p.matches("[default]CR/Rooms/R1/Status/Deep"));
    }

    @Test
    void starMidPathIsExactlyOneSegment() {
        TagPattern p = TagPattern.parse("[default]CR/Rooms/*/DP_1/Value");
        assertNotNull(p);
        assertFalse(p.isLiteral());
        assertEquals("[default]CR/Rooms", p.prefixPath());
        assertTrue(p.matches("[default]CR/Rooms/R1/DP_1/Value"));
        assertTrue(p.matches("[default]CR/Rooms/R2/DP_1/Value"));
        assertFalse(p.matches("[default]CR/Rooms/R1/DP_2/Value"));
        assertFalse(p.matches("[default]CR/Rooms/R1/Sub/DP_1/Value"));
        assertFalse(p.matches("[default]CR/Rooms/DP_1/Value"));
    }

    @Test
    void starInsideSegmentAndLeaf() {
        TagPattern p = TagPattern.parse("[default]CR/Rooms/*/HF_*/CFMFeedback");
        assertTrue(p.matches("[default]CR/Rooms/R1/HF_3/CFMFeedback"));
        assertFalse(p.matches("[default]CR/Rooms/R1/XHF_3/CFMFeedback"));
        TagPattern leaf = TagPattern.parse("[default]CR/Rooms/1*/Status");
        assertTrue(leaf.matches("[default]CR/Rooms/101/Status"));
        assertFalse(leaf.matches("[default]CR/Rooms/201/Status"));
        TagPattern leafStar = TagPattern.parse("[default]CR/Rooms/R1/Temp*");
        assertTrue(leafStar.matches("[default]CR/Rooms/R1/TempSP"));
        assertFalse(leafStar.matches("[default]CR/Rooms/R1/SetTemp"));
    }

    @Test
    void trailingStarIsDirectChildren() {
        TagPattern p = TagPattern.parse("[default]CR/Rooms/*");
        assertTrue(p.matchesLeaf(2, "Anything"));
        assertFalse(p.matchesLeaf(3, "Anything"));
        assertFalse(p.matchesFolder(2, "Anything"));
        assertEquals("[default]CR/Rooms", p.prefixPath());
    }

    @Test
    void doubleStarTailIsAnyDepth() {
        TagPattern p = TagPattern.parse("[default]CR/Rooms/**");
        assertTrue(p.isRecursive());
        assertFalse(p.isLiteral());
        assertTrue(p.matches("[default]CR/Rooms/R1/Status"));
        assertTrue(p.matches("[default]CR/Rooms/R1/DP_1/Value"));
        assertFalse(p.matches("[default]CR/Other/R1"));
        assertTrue(p.matchesFolder(5, "x"));
        TagPattern mixed = TagPattern.parse("[default]CR/Rooms/1*/**");
        assertTrue(mixed.matches("[default]CR/Rooms/101/A/B"));
        assertFalse(mixed.matches("[default]CR/Rooms/201/A/B"));
    }

    @Test
    void doubleStarNotLastIsInvalid() {
        assertNull(TagPattern.parse("[default]CR/**/Status"));
        assertNull(TagPattern.parse("[default]CR/Ro**ms/X"));
    }

    @Test
    void malformedPatternsAreInvalid() {
        assertNull(TagPattern.parse(null));
        assertNull(TagPattern.parse("CR/Rooms/*"));
        assertNull(TagPattern.parse("[]CR/X"));
        assertNull(TagPattern.parse("[def*]CR/X"));
        assertNull(TagPattern.parse("[default]CR//X"));
        assertNull(TagPattern.parse("[default]CR/../X"));
        assertNull(TagPattern.parse("[default]"));
    }

    @Test
    void bracketsInProviderAndSegmentsAreLiteral() {
        TagPattern p = TagPattern.parse("[my.prov]Area/Tag[1]/*");
        assertNotNull(p);
        assertEquals("my.prov", p.provider());
        assertEquals("[my.prov]Area/Tag[1]", p.prefixPath());
        assertTrue(p.matches("[my.prov]Area/Tag[1]/V"));
        assertFalse(p.matches("[my.prov]Area/Tag1/V"));
        TagPattern q = TagPattern.parse("[default]A/T[0-9]*/V");
        assertTrue(q.matches("[default]A/T[0-9]x/V"));
        assertFalse(q.matches("[default]A/T5x/V"));
    }

    @Test
    void providerNameComparesCaseInsensitively() {
        assertTrue(TagPattern.parse("[Default]A/*").matches("[default]A/B"));
    }

    @Test
    void legacyFolderPatternsStillMean() {
        TagPattern children = TagPattern.parse("[default]Launch/Clock/*");
        assertTrue(children.matches("[default]Launch/Clock/Phase"));
        assertFalse(children.matches("[default]Launch/Clock/Sub/Phase"));
        TagPattern deep = TagPattern.parse("[default]Launch/**");
        assertTrue(deep.matches("[default]Launch/Clock/Sub/Phase"));
        assertFalse(deep.matches("[default]Launch"));
    }
}
