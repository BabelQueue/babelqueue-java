package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared cross-SDK conformance suite (vendored under
 * {@code src/test/resources/conformance}) against this core — the same fixtures
 * every BabelQueue SDK must satisfy. Per-message fields (meta.id, trace_id,
 * meta.created_at) are intrinsically unique and are checked for presence only.
 */
class ConformanceTest {

    @SuppressWarnings("unchecked")
    private static final Map<String, Object> MANIFEST =
        (Map<String, Object>) Json.parse(readResource("/conformance/manifest.json"));

    @Test
    void manifestMatchesCoreSchemaVersion() {
        assertEquals(EnvelopeCodec.SCHEMA_VERSION, ((Number) MANIFEST.get("schema_version")).intValue());
        assertFalse(((List<?>) MANIFEST.get("cases")).isEmpty());
    }

    @TestFactory
    Stream<DynamicTest> conformanceCases() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) MANIFEST.get("cases");
        List<DynamicTest> tests = new ArrayList<>();

        for (Map<String, Object> c : cases) {
            String name = (String) c.get("name");
            tests.add(dynamicTest(name, () -> runCase(c)));
        }
        return tests.stream();
    }

    private void runCase(Map<String, Object> c) {
        String body = readResource("/conformance/" + c.get("file"));
        Envelope env = EnvelopeCodec.decode(body);
        boolean valid = Boolean.TRUE.equals(c.get("valid"));

        if (!valid) {
            assertFalse(EnvelopeCodec.accepts(env),
                "invalid fixture must be rejected (" + c.getOrDefault("reason", "") + ")");
            return;
        }

        assertTrue(EnvelopeCodec.accepts(env), "valid fixture must be accepted");

        @SuppressWarnings("unchecked")
        Map<String, Object> expect = (Map<String, Object>) c.get("expect");

        assertEquals(expect.get("urn"), EnvelopeCodec.urn(env));
        assertEquals(((Number) expect.get("attempts")).intValue(), env.attempts());
        assertEquals(expect.get("lang"), env.meta().lang());
        assertEquals(((Number) expect.get("schema_version")).intValue(), env.meta().schemaVersion());

        if (expect.get("data") != null) {
            assertEquals(expect.get("data"), env.data());
        }

        assertNotNull(env.traceId());
        assertNotNull(env.meta().id());
        assertTrue(env.meta().createdAt() != 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> expectedDl = (Map<String, Object>) expect.get("dead_letter");
        if (expectedDl != null) {
            assertNotNull(env.deadLetter(), "expected a dead_letter block");
            if (expectedDl.get("reason") != null) {
                assertEquals(expectedDl.get("reason"), env.deadLetter().reason());
            }
            if (expectedDl.get("original_queue") != null) {
                assertEquals(expectedDl.get("original_queue"), env.deadLetter().originalQueue());
            }
        }
    }

    private static String readResource(String path) {
        try (InputStream in = ConformanceTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
