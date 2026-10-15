package com.ignite.gateway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import org.junit.jupiter.api.Test;

class SignatureUtilTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void absentSignatureAndReason() {
        SignatureUtil.Request r = SignatureUtil.parse(json("{}"));
        assertNull(r.signature);
        assertNull(r.reason);
        assertNull(SignatureUtil.parse(null).signature);
    }

    @Test
    void reasonOnly() {
        SignatureUtil.Request r = SignatureUtil.parse(json("{\"reason\":\"batch 42\"}"));
        assertNull(r.signature);
        assertEquals("batch 42", r.reason);
    }

    @Test
    void fullSignatureNormalisesMeaning() {
        SignatureUtil.Request r = SignatureUtil.parse(json(
                "{\"reason\":\"r\",\"signature\":{\"username\":\"op\",\"password\":\"pw\",\"meaning\":\"approved\"}}"));
        assertEquals("op", r.signature.username);
        assertEquals("pw", r.signature.password);
        assertEquals("APPROVED", r.signature.meaning);
    }

    @Test
    void partialSignatureIsBadRequest() {
        assertThrows(AuthUtil.BadRequestException.class, () -> SignatureUtil.parse(json(
                "{\"signature\":{\"username\":\"op\",\"meaning\":\"OTHER\"}}")));
        assertThrows(AuthUtil.BadRequestException.class, () -> SignatureUtil.parse(json(
                "{\"signature\":{\"username\":\"op\",\"password\":\"\",\"meaning\":\"OTHER\"}}")));
        assertThrows(AuthUtil.BadRequestException.class, () -> SignatureUtil.parse(json("{\"signature\":\"x\"}")));
    }

    @Test
    void meaningMustBeKnown() {
        assertThrows(AuthUtil.BadRequestException.class, () -> SignatureUtil.parse(json(
                "{\"signature\":{\"username\":\"op\",\"password\":\"pw\",\"meaning\":\"WHATEVER\"}}")));
        for (String m : SignatureUtil.MEANINGS) {
            SignatureUtil.Request r = SignatureUtil.parse(json(
                    "{\"signature\":{\"username\":\"op\",\"password\":\"pw\",\"meaning\":\"" + m + "\"}}"));
            assertEquals(m, r.signature.meaning);
        }
    }

    @Test
    void reasonLengthLimit() {
        String ok = "x".repeat(200);
        assertEquals(ok, SignatureUtil.parse(json("{\"reason\":\"" + ok + "\"}")).reason);
        assertThrows(AuthUtil.BadRequestException.class,
                () -> SignatureUtil.parse(json("{\"reason\":\"" + "x".repeat(201) + "\"}")));
    }

    @Test
    void actorMismatch() {
        SignatureUtil.Signature s = SignatureUtil.parse(json(
                "{\"signature\":{\"username\":\"op\",\"password\":\"pw\",\"meaning\":\"OTHER\"}}")).signature;
        assertTrue(SignatureUtil.matchesActor(s, "op"));
        assertTrue(SignatureUtil.matchesActor(s, "OP"));
        assertFalse(SignatureUtil.matchesActor(s, "someone-else"));
        assertFalse(SignatureUtil.matchesActor(s, "anonymous"));
        assertFalse(SignatureUtil.matchesActor(s, "api-key:site"));
        assertFalse(SignatureUtil.matchesActor(s, null));
        assertFalse(SignatureUtil.matchesActor(null, "op"));
    }

    @Test
    void actionValueFormatting() {
        SignatureUtil.Signature s = SignatureUtil.parse(json(
                "{\"signature\":{\"username\":\"op\",\"password\":\"pw\",\"meaning\":\"CHANGED\"}}")).signature;
        assertEquals("1 -> 2", SignatureUtil.actionValue("1 -> 2", null, null));
        assertEquals("1 -> 2 | signed: CHANGED", SignatureUtil.actionValue("1 -> 2", s, null));
        assertEquals("1 -> 2 | reason: fix", SignatureUtil.actionValue("1 -> 2", null, "fix"));
        assertEquals("1 -> 2 | signed: CHANGED | reason: fix", SignatureUtil.actionValue("1 -> 2", s, "fix"));
    }

    @Test
    void actionValueTruncatedTo255() {
        String v = SignatureUtil.actionValue("a".repeat(300), null, "r");
        assertEquals(255, v.length());
        assertTrue(v.endsWith("…"));
        String exact = "b".repeat(255);
        assertEquals(exact, SignatureUtil.actionValue(exact, null, null));
    }
}
