package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.babelqueue.idempotency.Idempotent;
import com.babelqueue.idempotency.InMemoryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the {@code trim}, {@code job_type} and {@code idempotency} sections of the vendored
 * cross-SDK conformance manifest (ADR-0032 section 5 and 6, ADR-0022). Each case is recorded
 * into {@code results.json} through {@link ConformanceResults}.
 */
@SuppressWarnings("unchecked")
class ConformanceSectionsTest {

    private static final Map<String, Object> MANIFEST = ConformanceResults.manifest();

    // ---- trim ----------------------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> trim() {
        Map<String, Object> section = (Map<String, Object>) MANIFEST.get("trim");
        assertNotNull(section, "manifest is missing the 'trim' section");
        List<DynamicTest> tests = new ArrayList<>();
        List<Object> cases = (List<Object>) section.get("cases");
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> c = (Map<String, Object>) cases.get(i);
            String id = idOf(c, "trim", i);
            tests.add(dynamicTest(id, ConformanceResults.record("trim", id, () -> runTrim(c))));
        }
        return tests.stream();
    }

    private static void runTrim(Map<String, Object> c) {
        String mode = (String) c.get("mode");
        switch (mode) {
            case "make" -> {
                String urn = (String) c.get("urn");
                String traceId = (String) c.get("trace_id");
                Object expect = c.get("expect");
                if ("reject".equals(expect)) {
                    assertThrows(BabelQueueException.class,
                        () -> EnvelopeCodec.make(urn, Map.of(), "default", traceId, null));
                    return;
                }
                Map<String, Object> want = (Map<String, Object>) expect;
                Envelope env = EnvelopeCodec.make(urn, Map.of(), "default", traceId, null);
                assertEquals(want.get("job"), env.job());
                if (want.containsKey("trace_id")) {
                    assertEquals(want.get("trace_id"), env.traceId());
                }
            }
            case "decode" -> runDecode(c);
            case "accepts" -> {
                Envelope env = EnvelopeCodec.decode(ConformanceResults.read("/conformance/" + c.get("file")));
                if (Boolean.TRUE.equals(c.get("expect_decode_ok"))) {
                    assertNotNull(env, "decode itself must succeed");
                }
                assertEquals(c.get("expect_accepts"), EnvelopeCodec.accepts(env));
            }
            case "reemit" -> {
                Envelope env = EnvelopeCodec.decode(ConformanceResults.read("/conformance/" + c.get("file")));
                Envelope hop = env.withAttempts(((Number) c.get("expect_attempts")).intValue());
                Object out = Json.parse(EnvelopeCodec.encode(hop));
                Map<String, Object> preserved = (Map<String, Object>) c.get("expect_preserved");
                List<String> violations = BehaviourConformanceTest.preservedViolations(out, preserved);
                assertTrue(violations.isEmpty(), "re-emit must not trim: " + violations);
            }
            default -> throw new AssertionError("unknown trim mode: " + mode);
        }
    }

    // ---- job_type ------------------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> jobType() {
        Map<String, Object> section = (Map<String, Object>) MANIFEST.get("job_type");
        assertNotNull(section, "manifest is missing the 'job_type' section");
        List<DynamicTest> tests = new ArrayList<>();
        List<Object> cases = (List<Object>) section.get("cases");
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> c = (Map<String, Object>) cases.get(i);
            String id = idOf(c, "job_type", i);
            tests.add(dynamicTest(id, ConformanceResults.record("job_type", id, () -> runDecode(c))));
        }
        return tests.stream();
    }

    /** Shared by {@code trim} (mode decode) and {@code job_type}: decode, resolved urn, warning flag. */
    private static void runDecode(Map<String, Object> c) {
        String raw = ConformanceResults.read("/conformance/" + c.get("file"));
        List<String> warnings = new ArrayList<>();
        if (!Boolean.TRUE.equals(c.get("valid"))) {
            // Java's decode never throws: "decode raises" maps to the idiomatic decode failure, an
            // envelope without a resolved identity that the consumer rejects (accepts == false).
            // Whether a warning precedes the failure is not asserted.
            Envelope rejected = EnvelopeCodec.decode(raw, warnings::add);
            assertEquals("", EnvelopeCodec.urn(rejected), "no valid job and no valid urn: no identity");
            assertFalse(EnvelopeCodec.accepts(rejected), "an envelope without identity must be rejected");
            return;
        }
        Envelope env = EnvelopeCodec.decode(raw, warnings::add);
        Map<String, Object> expect = (Map<String, Object>) c.get("expect");
        assertEquals(expect.get("urn"), EnvelopeCodec.urn(env));
        if (c.containsKey("expect_warning")) {
            assertEquals(Boolean.TRUE.equals(c.get("expect_warning")), !warnings.isEmpty(),
                "warning expectation (warnings: " + warnings + ")");
        }
    }

    // ---- idempotency -----------------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> idempotency() {
        Map<String, Object> section = (Map<String, Object>) MANIFEST.get("idempotency");
        assertNotNull(section, "manifest is missing the 'idempotency' section");
        List<DynamicTest> tests = new ArrayList<>();

        tests.add(dynamicTest("dedup_key", ConformanceResults.record("idempotency", "dedup_key", () -> {
            Map<String, Object> key = (Map<String, Object>) section.get("dedup_key");
            assertEquals("meta.id", key.get("key_field"));
            Envelope env = EnvelopeCodec.decode(ConformanceResults.read("/conformance/" + key.get("envelope_file")));
            assertEquals(key.get("expected_key"), env.meta().id());
        })));

        Map<String, Object> sequences = (Map<String, Object>) section.get("sequences");
        List<Object> cases = (List<Object>) sequences.get("cases");
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> c = (Map<String, Object>) cases.get(i);
            String name = c.get("name") instanceof String n ? n : String.valueOf(i); // sub-key: <index> only
            tests.add(dynamicTest("sequences/" + name,
                ConformanceResults.record("idempotency", "sequences/" + name, () -> runSequence(c))));
        }
        return tests.stream();
    }

    private static void runSequence(Map<String, Object> c) {
        InMemoryStore store = new InMemoryStore();
        int[] invocations = {0};
        List<String> outcome = new ArrayList<>();
        var handler = Idempotent.wrap(store, env -> {
            invocations[0]++; // expected_effects counts every handler run, including one that then throws
            if ("throw".equals(outcome.get(0))) {
                throw new IllegalStateException("handler failed");
            }
        });

        List<Object> deliveries = (List<Object>) c.get("deliveries");
        for (int i = 0; i < deliveries.size(); i++) {
            Map<String, Object> d = (Map<String, Object>) deliveries.get(i);
            String id = (String) d.get("meta_id");
            if (Boolean.TRUE.equals(d.get("forget_before"))) {
                store.forget(id);
            }
            Envelope env = new Envelope("urn:acme:t", "t", Map.of(),
                new Meta(id, "default", "java", 1, 1L), 0, null);

            outcome.clear();
            outcome.add((String) d.getOrDefault("outcome", "ok"));
            int before = invocations[0];
            if ("throw".equals(outcome.get(0))) {
                assertThrows(IllegalStateException.class, () -> handler.handle(env));
            } else {
                try {
                    handler.handle(env);
                } catch (Exception e) {
                    throw new AssertionError("delivery " + i + " must not throw", e);
                }
            }
            boolean ran = invocations[0] > before;
            assertEquals(d.get("expect_effect"), ran ? "run" : "skip", "delivery " + i);
        }
        assertEquals(((Number) c.get("expected_effects")).intValue(), invocations[0], "total effects");
        assertFalse(deliveries.isEmpty());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String idOf(Map<String, Object> c, String owner, int index) {
        return c.get("name") instanceof String s ? s : owner + "#" + index;
    }
}
