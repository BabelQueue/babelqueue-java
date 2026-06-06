package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvelopeCodecTest {

    @Test
    void makeProducesCanonicalShape() {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1042L));

        assertEquals("urn:babel:orders:created", env.job());
        assertEquals(0, env.attempts());
        assertEquals(EnvelopeCodec.SOURCE_LANG, env.meta().lang());
        assertEquals(EnvelopeCodec.SCHEMA_VERSION, env.meta().schemaVersion());
        assertEquals("default", env.meta().queue());
        assertTrue(env.traceId() != null && !env.traceId().isBlank());
        assertTrue(env.meta().id() != null && !env.meta().id().isBlank());
        assertNotEquals(env.traceId(), env.meta().id());
        assertTrue(env.meta().createdAt() > 0);
        assertNull(env.deadLetter());
    }

    @Test
    void makeRejectsBlankUrn() {
        assertThrows(BabelQueueException.class, () -> EnvelopeCodec.make("   ", Map.of()));
    }

    @Test
    void makeHonorsQueueAndTraceContinuation() {
        Envelope env =
            EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1L), "orders", "trace-123");

        assertEquals("orders", env.meta().queue());
        assertEquals("trace-123", env.traceId());
    }

    @Test
    void encodeDecodeRoundTrips() {
        Envelope env =
            EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1042L), "orders", null);
        Envelope got = EnvelopeCodec.decode(EnvelopeCodec.encode(env));

        assertTrue(EnvelopeCodec.accepts(got));
        assertEquals(env.job(), EnvelopeCodec.urn(got));
        assertEquals(env.traceId(), got.traceId());
        assertEquals(env.meta().id(), got.meta().id());
        assertEquals(1042L, got.data().get("order_id"));
    }

    @Test
    void encodeIsCanonicalAndUnescaped() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", "Café — naïve ☕ A & B <x>/y");
        data.put("qty", 7L);
        data.put("ratio", 0.5);
        data.put("active", true);
        data.put("note", null);

        Envelope env = new Envelope(
            "urn:babel:catalog:item.indexed",
            "t-123",
            data,
            new Meta("m-1", "catalog", "java", 1, 1749132727000L),
            2,
            null);

        String expected =
            "{\"job\":\"urn:babel:catalog:item.indexed\",\"trace_id\":\"t-123\","
                + "\"data\":{\"title\":\"Café — naïve ☕ A & B <x>/y\",\"qty\":7,\"ratio\":0.5,"
                + "\"active\":true,\"note\":null},"
                + "\"meta\":{\"id\":\"m-1\",\"queue\":\"catalog\",\"lang\":\"java\","
                + "\"schema_version\":1,\"created_at\":1749132727000},\"attempts\":2}";

        assertEquals(expected, EnvelopeCodec.encode(env));
    }

    @Test
    void decodeResolvesUrnAlias() {
        String raw =
            "{\"urn\":\"urn:babel:orders:created\",\"trace_id\":\"t\",\"data\":{},"
                + "\"meta\":{\"id\":\"i\",\"queue\":\"q\",\"lang\":\"java\","
                + "\"schema_version\":1,\"created_at\":1},\"attempts\":0}";
        Envelope env = EnvelopeCodec.decode(raw);

        assertEquals("urn:babel:orders:created", EnvelopeCodec.urn(env));
        assertTrue(EnvelopeCodec.accepts(env));
    }

    @Test
    void decodeReturnsEmptyForMalformedInput() {
        assertFalse(EnvelopeCodec.accepts(EnvelopeCodec.decode("not json")));
        assertFalse(EnvelopeCodec.accepts(EnvelopeCodec.decode("[1,2,3]")));
        assertFalse(EnvelopeCodec.accepts(EnvelopeCodec.decode("42")));
    }

    @Test
    void acceptsRejectsMalformedEnvelopes() {
        Envelope ok = EnvelopeCodec.make("urn:babel:orders:created", Map.of("x", 1L));
        assertTrue(EnvelopeCodec.accepts(ok));

        assertFalse(EnvelopeCodec.accepts(
            new Envelope(null, ok.traceId(), ok.data(), ok.meta(), 0, null)));
        assertFalse(EnvelopeCodec.accepts(
            new Envelope(ok.job(), ok.traceId(), ok.data(),
                new Meta("i", "q", "java", 2, 1L), 0, null)));
        assertFalse(EnvelopeCodec.accepts(
            new Envelope(ok.job(), "  ", ok.data(), ok.meta(), 0, null)));
        assertFalse(EnvelopeCodec.accepts(
            new Envelope(ok.job(), ok.traceId(), null, ok.meta(), 0, null)));
    }

    @Test
    void fromMessageBuildsAndContinuesTrace() {
        record OrderCreated(long orderId) implements PolyglotMessage, HasTraceId {
            @Override
            public String getBabelUrn() {
                return "urn:babel:orders:created";
            }

            @Override
            public Map<String, Object> toPayload() {
                return Map.of("order_id", orderId);
            }

            @Override
            public String getBabelTraceId() {
                return "carry-over";
            }
        }

        Envelope env = EnvelopeCodec.fromMessage(new OrderCreated(7L), "orders");

        assertEquals("urn:babel:orders:created", env.job());
        assertEquals(7L, env.data().get("order_id"));
        assertEquals("carry-over", env.traceId());
        assertEquals("orders", env.meta().queue());
    }
}
