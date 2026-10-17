package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unknown-key preservation (message-envelope.md §4/§8) and forbidden-key handling (§10, K-15). */
@SuppressWarnings("unchecked")
class EnvelopeExtrasTest {

    private static final String RAW = "{\"job\":\"urn:babel:orders:created\",\"trace_id\":\"t-1\","
        + "\"data\":{\"id\":1},\"meta\":{\"id\":\"m-1\",\"queue\":\"orders\",\"lang\":\"php\","
        + "\"schema_version\":1,\"created_at\":1700000000000,\"vendor_flag\":true,\"ts\":5,"
        + "\"vendor_ctx\":{\"hops\":[1,2]}},\"attempts\":0,\"zeta\":\"z\",\"timestamp\":1,\"alpha\":[1]}";

    @Test
    void decodeCollectsUnknownKeysInOrderAndDropsForbiddenOnes() {
        List<String> warnings = new ArrayList<>();
        Envelope env = EnvelopeCodec.decode(RAW, warnings::add);

        assertEquals(List.of("zeta", "alpha"), new ArrayList<>(env.extras().keySet()));
        assertEquals(List.of("vendor_flag", "vendor_ctx"), new ArrayList<>(env.meta().extras().keySet()));
        assertFalse(env.extras().containsKey("timestamp"));
        assertFalse(env.meta().extras().containsKey("ts"));
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("/timestamp")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("/meta/ts")));
    }

    @Test
    void encodeWritesKnownFieldsFirstThenExtras() {
        String out = EnvelopeCodec.encode(EnvelopeCodec.decode(RAW, w -> { }));
        assertEquals("{\"job\":\"urn:babel:orders:created\",\"trace_id\":\"t-1\",\"data\":{\"id\":1},"
            + "\"meta\":{\"id\":\"m-1\",\"queue\":\"orders\",\"lang\":\"php\",\"schema_version\":1,"
            + "\"created_at\":1700000000000,\"vendor_flag\":true,\"vendor_ctx\":{\"hops\":[1,2]}},"
            + "\"attempts\":0,\"zeta\":\"z\",\"alpha\":[1]}", out);
    }

    @Test
    void encodeRejectsForbiddenOrShadowingExtrasInsteadOfSkippingThem() {
        // ADR-0031 (c): the silent skip is gone; every offending key throws, naming its pointer.
        for (String key : List.of("timestamp", "urn", "job", "trace_id", "data", "meta", "attempts", "dead_letter")) {
            Envelope env = envelopeWithTop(Map.of(key, "x"));
            BabelQueueException ex = assertThrows(BabelQueueException.class, () -> EnvelopeCodec.encode(env), key);
            assertTrue(ex.getMessage().contains("/" + key), ex.getMessage());
        }
        for (String key : List.of("max_retries", "attempts", "source", "ts", "id", "queue", "lang",
            "schema_version", "created_at")) {
            Meta meta = new Meta("m", "q", "java", 1, 1L, Map.of(key, 3));
            Envelope env = new Envelope("urn:babel:a:b", "t", Map.of(), meta, 0, null);
            BabelQueueException ex = assertThrows(BabelQueueException.class, () -> EnvelopeCodec.encode(env), key);
            assertTrue(ex.getMessage().contains("/meta/" + key), ex.getMessage());
        }
    }

    @Test
    void decodedMessageWithForbiddenKeysStillReEncodes() {
        // Decode drops forbidden keys (with a warning), so the re-emit never trips the encode reject.
        Map<String, Object> out = (Map<String, Object>) Json.parse(
            EnvelopeCodec.encode(EnvelopeCodec.decode(RAW, w -> { })));
        assertFalse(out.containsKey("timestamp"));
        assertFalse(((Map<String, Object>) out.get("meta")).containsKey("ts"));
    }

    @Test
    void makeWithMetaWritesEntriesNextToCoreKeys() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("vendor_flag", true);
        meta.put("ext", Map.of("acme", Map.of("tier", "gold")));
        Envelope env = EnvelopeCodec.make("urn:babel:a:b", Map.of("id", 1), "orders", null, meta);

        Map<String, Object> out = (Map<String, Object>) Json.parse(EnvelopeCodec.encode(env));
        Map<String, Object> outMeta = (Map<String, Object>) out.get("meta");
        assertEquals(true, outMeta.get("vendor_flag"));
        assertEquals("orders", outMeta.get("queue"));
        assertEquals(Map.of("acme", Map.of("tier", "gold")), outMeta.get("ext"));

        Envelope plain = EnvelopeCodec.make("urn:babel:a:b", Map.of(), "orders", null, null);
        assertTrue(plain.meta().extras().isEmpty());
    }

    @Test
    void makeWithMetaRejectsCoreAndForbiddenKeys() {
        for (String key : List.of("id", "queue", "lang", "schema_version", "created_at",
            "max_retries", "attempts", "source", "ts")) {
            assertThrows(BabelQueueException.class,
                () -> EnvelopeCodec.make("urn:babel:a:b", Map.of(), "q", null, Map.of(key, 1)), key);
        }
    }

    private static Envelope envelopeWithTop(Map<String, Object> top) {
        return new Envelope("urn:babel:a:b", "t", Map.of(), new Meta("m", "q", "java", 1, 1L), 0, null, top);
    }

    @Test
    void reEmitPathsCarryExtras() {
        Envelope env = EnvelopeCodec.decode(RAW, w -> { });

        Envelope retried = env.withAttempts(3);
        assertEquals(env.extras(), retried.extras());
        assertEquals(env.meta(), retried.meta());

        Envelope dead = DeadLetters.annotate(env, "failed", "orders", 3, "boom", "X");
        assertEquals(env.extras(), dead.extras());
        assertEquals(env.meta().extras(), dead.meta().extras());

        Envelope reset = Redrive.reset(dead);
        assertNull(reset.deadLetter());
        assertEquals(0, reset.attempts());
        assertEquals(env.extras(), reset.extras());
        assertEquals(env.meta().extras(), reset.meta().extras());
    }

    @Test
    void legacyConstructorsYieldEmptyImmutableExtras() {
        Envelope env = new Envelope("urn:a", "t", Map.of(), new Meta("m", "q", "java", 1, 1L), 0, null);
        assertTrue(env.extras().isEmpty());
        assertTrue(env.meta().extras().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> env.extras().put("k", 1));
    }

    @Test
    void urnAliasIsNotCapturedAsExtra() {
        Envelope env = EnvelopeCodec.decode("{\"urn\":\"urn:babel:a:b\",\"trace_id\":\"t\",\"data\":{},"
            + "\"meta\":{\"id\":\"m\",\"queue\":\"q\",\"lang\":\"go\",\"schema_version\":1,\"created_at\":1},"
            + "\"attempts\":0}", w -> { });
        assertEquals("urn:babel:a:b", env.job());
        assertTrue(env.extras().isEmpty());
    }

    @Test
    void jobAndUrnTogetherKeepJobDropUrnAndWarn() {
        List<String> warnings = new ArrayList<>();
        Envelope env = EnvelopeCodec.decode("{\"job\":\"urn:babel:a:b\",\"urn\":\"urn:babel:x:y\","
            + "\"trace_id\":\"t\",\"data\":{},\"meta\":{\"id\":\"m\",\"queue\":\"q\",\"lang\":\"go\","
            + "\"schema_version\":1,\"created_at\":1},\"attempts\":0}", warnings::add);
        assertEquals("urn:babel:a:b", env.job());
        assertTrue(env.extras().isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("/urn")), warnings.toString());
    }

    @Test
    void blankJobUsesClosedAsciiSetOnly() {
        String tail = ",\"urn\":\"urn:babel:b:c\",\"trace_id\":\"t\",\"data\":{},\"meta\":{\"id\":\"m\","
            + "\"queue\":\"q\",\"lang\":\"go\",\"schema_version\":1,\"created_at\":1},\"attempts\":0}";
        // ASCII blank (space, tab, newline, VT, FF, CR) falls back to urn, silently.
        List<String> warnings = new ArrayList<>();
        Envelope ascii = EnvelopeCodec.decode("{\"job\":\" \\t\\n\\u000b\\f\\r\"" + tail, warnings::add);
        assertEquals("urn:babel:b:c", ascii.job());
        assertTrue(warnings.isEmpty(), warnings.toString());
        // U+2003 is not blank: job wins, urn dropped with a warning, no fallback.
        warnings.clear();
        Envelope unicode = EnvelopeCodec.decode("{\"job\":\"\\u2003\"" + tail, warnings::add);
        assertEquals("\u2003", unicode.job());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("/urn")), warnings.toString());
        assertTrue(EnvelopeCodec.accepts(unicode), "a U+2003 job is a non-blank identity");
    }
}
