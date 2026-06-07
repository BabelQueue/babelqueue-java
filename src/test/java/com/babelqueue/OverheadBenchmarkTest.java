package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * GR-8 budget: the envelope encode/decode path must add no more than 2% over plain
 * JSON serialization (the baseline a publisher already pays), measured against a
 * conservative broker round-trip. Pure CPU — no broker — so the gate is stable and
 * environment-independent in CI. Same methodology + reference as every other SDK.
 */
class OverheadBenchmarkTest {

    // Conservative networked broker round-trip (ns): local loopback Redis measures
    // ~300µs; production brokers are slower, so 750µs is conservative.
    private static final long REFERENCE_BROKER_ROUNDTRIP_NS = 750_000L;

    private static Map<String, Object> data() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_id", 1042L);
        m.put("amount", 99.9);
        m.put("currency", "USD");
        m.put("note", "café ☕");
        return m;
    }

    @Test
    void codecOverheadWithinBudget() {
        Map<String, Object> data = data();

        Runnable envelope = () -> {
            String body = EnvelopeCodec.encode(EnvelopeCodec.make("urn:babel:orders:created", data));
            EnvelopeCodec.decode(body);
        };
        Runnable bare = () -> Json.parse(Json.write(data));

        double marginal = Math.max(0.0, nsPerOp(envelope) - nsPerOp(bare));
        double overhead = marginal / REFERENCE_BROKER_ROUNDTRIP_NS * 100;

        assertTrue(
                overhead <= 2.0,
                String.format(
                        "codec overhead %.2f%% exceeds the 2%% GR-8 budget (marginal %.0f ns)",
                        overhead, marginal));
    }

    private static double nsPerOp(Runnable fn) {
        for (int i = 0; i < 50_000; i++) { // warm up (JIT)
            fn.run();
        }
        int iterations = 200_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            fn.run();
        }
        return (double) (System.nanoTime() - start) / iterations;
    }
}
