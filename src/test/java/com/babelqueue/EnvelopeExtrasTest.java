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
    void encodeNeverEmitsForbiddenOrShadowingKeysFromExtras() {
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("timestamp", 1);
        top.put("job", "urn:evil");
        top.put("urn", "urn:evil");
        top.put("ok", 1);
        Map<String, Object> metaExtras = new LinkedHashMap<>();
        metaExtras.put("max_retries", 3);
        metaExtras.put("attempts", 3);
        metaExtras.put("source", "x");
        metaExtras.put("ts", 1);
        metaExtras.put("queue", "other");
        Meta meta = new Meta("m", "q", "java", 1, 1L, metaExtras);
        Envelope env = new Envelope("urn:babel:a:b", "t", Map.of(), meta, 0, null, top);

        Map<String, Object> out = (Map<String, Object>) Json.parse(EnvelopeCodec.encode(env));
        assertEquals("urn:babel:a:b", out.get("job"));
        assertFalse(out.containsKey("timestamp"));
        assertFalse(out.containsKey("urn"));
        assertEquals(1L, out.get("ok"));
        Map<String, Object> outMeta = (Map<String, Object>) out.get("meta");
        assertEquals("q", outMeta.get("queue"));
        for (String key : List.of("max_retries", "attempts", "source", "ts")) {
            assertFalse(outMeta.containsKey(key), key);
        }
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
}
