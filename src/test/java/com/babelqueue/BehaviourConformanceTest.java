package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.babelqueue.schema.PayloadValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the behavioural sections of the vendored cross-SDK conformance manifest:
 * {@code roundtrip}, {@code data_shape}, {@code forbidden_keys},
 * {@code payload_schema_unicode}, {@code extension_write} and {@code encode_reject}. Every section is mandatory — a missing or empty
 * section fails the build rather than being skipped.
 *
 * <p>Equality is deep and type-strict: the core JSON reader maps integers to
 * {@link Long}, fractions to {@link Double}, objects to {@link Map} and arrays to
 * {@link List}, so {@code true != 1}, {@code 1 != 1.0} and {@code {} != []} hold
 * under {@link Objects#equals}.
 */
@SuppressWarnings("unchecked")
class BehaviourConformanceTest {

    private static final Map<String, Object> MANIFEST =
        (Map<String, Object>) Json.parse(readResource("/conformance/manifest.json"));

    /** Sentinel for "no value at this pointer" (distinct from a JSON {@code null}). */
    private static final Object ABSENT = new Object();

    @TestFactory
    Stream<DynamicTest> roundtrip() {
        return cases("roundtrip").stream().map(c -> dynamicTest(name(c), () -> {
            // conformance/README.md (v1.4.0): "expect_attempts" and "expect_preserved" are required on every
            // roundtrip case (the other fields are optional). A case that loses one must fail, not silently verify less.
            assertNotNull(c.get("expect_attempts"), "roundtrip case lacks mandatory expect_attempts: " + name(c));
            assertNotNull(c.get("expect_preserved"), "roundtrip case lacks mandatory expect_preserved: " + name(c));
            List<String> violations = roundtripViolations(c, fixture(c));
            assertTrue(violations.isEmpty(), "roundtrip violations: " + violations);
        }));
    }

    /**
     * Evaluates one {@code roundtrip} case against {@code raw} and returns every violation
     * (empty = conformant). Covers {@code expect_attempts}, {@code expect_preserved},
     * {@code expect_absent_after_reencode}, {@code apply_dead_letter} (the SDK's own
     * {@link DeadLetters} helper, applied after the attempts+1 step, all checks then run on the
     * dead-letter copy) and {@code expect_dead_letter}, plus the "no decode warnings" and
     * {@code accepts()} rules.
     */
    static List<String> roundtripViolations(Map<String, Object> c, String raw) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Envelope decoded = EnvelopeCodec.decode(raw, warnings::add);
        if (!warnings.isEmpty()) {
            violations.add("decode produced warnings: " + warnings);
        }
        if (!EnvelopeCodec.accepts(decoded)) {
            violations.add("decoded envelope must be accepted");
        }

        Envelope reemitted = decoded.withAttempts(decoded.attempts() + 1);
        Map<String, Object> dl = (Map<String, Object>) c.get("apply_dead_letter");
        if (dl != null) {
            reemitted = DeadLetters.annotate(
                reemitted,
                (String) dl.get("reason"),
                (String) dl.get("original_queue"),
                reemitted.attempts(),
                "conformance runner",
                "ConformanceRunnerException");
        }
        Object out = Json.parse(EnvelopeCodec.encode(reemitted));

        if (c.containsKey("expect_attempts") && !Objects.equals(c.get("expect_attempts"), resolve(out, "/attempts"))) {
            violations.add("attempts: expected " + c.get("expect_attempts") + " got " + describe(resolve(out, "/attempts")));
        }
        violations.addAll(preservedViolations(out, (Map<String, Object>) c.get("expect_preserved")));
        violations.addAll(absentViolations(out, (List<Object>) c.get("expect_absent_after_reencode")));

        Map<String, Object> expectDl = (Map<String, Object>) c.get("expect_dead_letter");
        if (expectDl != null) {
            for (Map.Entry<String, Object> e : expectDl.entrySet()) {
                Object actual = resolve(out, "/dead_letter/" + e.getKey());
                if (actual == ABSENT || !Objects.equals(e.getValue(), actual)) {
                    violations.add("dead_letter." + e.getKey() + ": expected " + e.getValue() + " got " + describe(actual));
                }
            }
        }
        return violations;
    }

    /** Violations of {@code expect_preserved}: each pointer must resolve and equal the given value. */
    private static List<String> preservedViolations(Object out, Map<String, Object> preserved) {
        List<String> violations = new ArrayList<>();
        if (preserved == null) {
            return violations;
        }
        for (Map.Entry<String, Object> e : preserved.entrySet()) {
            Object actual = resolve(out, e.getKey());
            if (actual == ABSENT) {
                violations.add("pointer " + e.getKey() + " missing after re-encode");
            } else if (!Objects.equals(e.getValue(), actual)) {
                violations.add("pointer " + e.getKey() + ": expected " + e.getValue() + " got " + describe(actual));
            }
        }
        return violations;
    }

    /** Violations of {@code expect_absent_after_reencode}: no listed pointer may resolve. */
    private static List<String> absentViolations(Object out, List<Object> pointers) {
        List<String> violations = new ArrayList<>();
        if (pointers == null) {
            return violations;
        }
        for (Object pointer : pointers) {
            if (resolve(out, (String) pointer) != ABSENT) {
                violations.add(pointer + " must not be re-emitted");
            }
        }
        return violations;
    }

    private static String describe(Object value) {
        return value == ABSENT ? "<absent>" : String.valueOf(value);
    }

    @TestFactory
    Stream<DynamicTest> dataShape() {
        return cases("data_shape").stream().map(c -> dynamicTest(name(c), () -> {
            String mode = (String) c.get("mode");
            if ("encode".equals(mode)) {
                Envelope env = EnvelopeCodec.make(
                    (String) c.get("urn"),
                    (Map<String, Object>) c.get("data"),
                    (String) c.get("queue"),
                    null);
                String raw = rawMember(EnvelopeCodec.encode(env), "data");
                assertEquals(c.get("expect_encoded_data_json"), stripInsignificantWhitespace(raw));
            } else if ("decode".equals(mode)) {
                boolean valid;
                try {
                    valid = EnvelopeCodec.accepts(EnvelopeCodec.decode(fixture(c)));
                } catch (RuntimeException ex) {
                    valid = false;
                }
                assertEquals(c.get("valid"), valid, "decode verdict");
            } else {
                fail("unknown data_shape mode: " + mode);
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> forbiddenKeys() {
        return cases("forbidden_keys").stream().map(c -> dynamicTest(name(c), () -> {
            List<String> violations = forbiddenKeyViolations(c, fixture(c));
            assertTrue(violations.isEmpty(), "forbidden_keys violations: " + violations);
        }));
    }

    /**
     * Evaluates one {@code forbidden_keys} case against {@code raw}: the message is still accepted,
     * decode warns naming {@code forbidden_key}, and the re-emit satisfies
     * {@code expect_absent_after_reencode} and {@code expect_preserved}.
     */
    static List<String> forbiddenKeyViolations(Map<String, Object> c, String raw) {
        List<String> violations = new ArrayList<>();
        if (!"warn".equals(c.get("expect"))) {
            violations.add("unsupported expect: " + c.get("expect"));
        }
        String forbiddenKey = (String) c.get("forbidden_key");

        List<String> warnings = new ArrayList<>();
        Envelope decoded = EnvelopeCodec.decode(raw, warnings::add);
        if (!EnvelopeCodec.accepts(decoded)) {
            violations.add("forbidden key must not reject the message");
        }
        if (warnings.stream().noneMatch(w -> w.contains(forbiddenKey))) {
            violations.add("expected a warning naming " + forbiddenKey + ", got " + warnings);
        }

        Object out = Json.parse(EnvelopeCodec.encode(decoded));
        violations.addAll(absentViolations(out, (List<Object>) c.get("expect_absent_after_reencode")));
        violations.addAll(preservedViolations(out, (Map<String, Object>) c.get("expect_preserved")));
        return violations;
    }

    @TestFactory
    Stream<DynamicTest> payloadSchemaUnicode() {
        Map<String, Object> section = section("payload_schema_unicode");
        Map<String, Object> schema = (Map<String, Object>) section.get("schema");
        assertNotNull(schema, "payload_schema_unicode.schema");
        return cases("payload_schema_unicode").stream().map(c -> dynamicTest(name(c), () -> {
            boolean isValid = PayloadValidator.validate(schema, c.get("data")) == null;
            assertEquals(c.get("valid"), isValid, "validator verdict");
        }));
    }

    @TestFactory
    Stream<DynamicTest> extensionWrite() {
        return cases("extension_write").stream().map(c -> dynamicTest(name(c), () -> {
            Envelope made = EnvelopeCodec.make(
                (String) c.get("urn"),
                (Map<String, Object>) c.get("data"),
                (String) c.get("queue"),
                null,
                (Map<String, Object>) c.get("meta"));
            String encoded = EnvelopeCodec.encode(made);
            Map<String, Object> expected = (Map<String, Object>) c.get("expect_envelope");

            List<String> violations = new ArrayList<>(preservedViolations(Json.parse(encoded), expected));
            assertTrue(violations.isEmpty(), "first emit: " + violations);

            // Re-emit chain: decode, attempts + 1, encode; the extension keys must survive the hop.
            Envelope hop = EnvelopeCodec.decode(encoded).withAttempts(1);
            Object reemitted = Json.parse(EnvelopeCodec.encode(hop));
            violations.addAll(preservedViolations(reemitted, expected));
            if (!Objects.equals(1L, resolve(reemitted, "/attempts"))) {
                violations.add("/attempts must be 1 after the hop, got " + describe(resolve(reemitted, "/attempts")));
            }
            assertTrue(violations.isEmpty(), "re-emit chain: " + violations);
        }));
    }

    @TestFactory
    Stream<DynamicTest> encodeReject() {
        return cases("encode_reject").stream().map(c -> dynamicTest(name(c), () -> {
            // A JSON object cannot express a repeated key and make() takes one map per call.
            Assumptions.assumeTrue(!c.containsKey("meta_entries"), "repeated meta keys are inexpressible in Java");
            assertEquals("reject", c.get("expect"));

            BabelQueueException ex = assertThrows(BabelQueueException.class, () -> {
                Envelope env = EnvelopeCodec.make(
                    (String) c.get("urn"),
                    (Map<String, Object>) c.get("data"),
                    (String) c.get("queue"),
                    null,
                    (Map<String, Object>) c.get("meta"));
                Map<String, Object> extrasTop = (Map<String, Object>) c.get("extras_top");
                Map<String, Object> extrasMeta = (Map<String, Object>) c.get("extras_meta");
                if (extrasTop != null || extrasMeta != null) {
                    Meta m = env.meta();
                    Map<String, Object> metaExtras = new LinkedHashMap<>(m.extras());
                    if (extrasMeta != null) {
                        metaExtras.putAll(extrasMeta);
                    }
                    env = new Envelope(
                        env.job(),
                        env.traceId(),
                        env.data(),
                        new Meta(m.id(), m.queue(), m.lang(), m.schemaVersion(), m.createdAt(), metaExtras),
                        env.attempts(),
                        env.deadLetter(),
                        extrasTop == null ? Map.of() : extrasTop);
                }
                EnvelopeCodec.encode(env);
            });
            String offending = (String) c.get("offending_key");
            assertTrue(offending == null || ex.getMessage().contains(offending),
                "message should name " + offending + ": " + ex.getMessage());
        }));
    }

    // --- the runner must really detect violations (a runner that ignores a field passes everything) ---

    private static final String SYNTH_RAW = "{\"job\":\"urn:acme:a\",\"trace_id\":\"t-1\",\"data\":{\"k\":1},"
        + "\"meta\":{\"id\":\"m-1\",\"queue\":\"orders\",\"lang\":\"php\",\"schema_version\":1,"
        + "\"created_at\":1,\"vendor_flag\":true},\"attempts\":0,\"extra_top\":1}";

    private static Map<String, Object> synthetic(Object... kv) {
        Map<String, Object> c = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            c.put((String) kv[i], kv[i + 1]);
        }
        return c;
    }

    @Test
    void runnerReportsConformantSyntheticCaseAsClean() {
        Map<String, Object> c = synthetic(
            "expect_attempts", 1L,
            "expect_preserved", Map.of("/job", "urn:acme:a", "/extra_top", 1L, "/meta/vendor_flag", true),
            "expect_absent_after_reencode", List.of("/urn", "/timestamp"),
            "apply_dead_letter", Map.of("reason", "failed", "original_queue", "orders"),
            "expect_dead_letter", Map.of("reason", "failed", "original_queue", "orders"));
        assertEquals(List.of(), roundtripViolations(c, SYNTH_RAW));
    }

    @Test
    void runnerReportsPresentPointerAsExpectAbsentViolation() {
        Map<String, Object> c = synthetic("expect_attempts", 1L, "expect_absent_after_reencode", List.of("/job", "/meta/vendor_flag"));
        List<String> v = roundtripViolations(c, SYNTH_RAW);
        assertEquals(2, v.size(), v.toString());
        assertTrue(v.get(0).contains("/job"), v.toString());
        assertTrue(v.get(1).contains("/meta/vendor_flag"), v.toString());
    }

    @Test
    void runnerReportsMissingOrDifferentValueAsExpectPreservedViolation() {
        Map<String, Object> c = synthetic(
            "expect_attempts", 1L,
            "expect_preserved", Map.of("/job", "urn:acme:other", "/nope", 1L, "/extra_top", 1L));
        List<String> v = roundtripViolations(c, SYNTH_RAW);
        assertEquals(2, v.size(), v.toString());
        assertTrue(v.stream().anyMatch(m -> m.contains("/job") && m.contains("urn:acme:other")), v.toString());
        assertTrue(v.stream().anyMatch(m -> m.contains("/nope") && m.contains("missing")), v.toString());
    }

    @Test
    void runnerReportsWrongDeadLetterFieldsAndAttempts() {
        Map<String, Object> c = synthetic(
            "expect_attempts", 9L,
            "apply_dead_letter", Map.of("reason", "failed", "original_queue", "orders"),
            "expect_dead_letter", Map.of("reason", "expired", "original_queue", "orders", "missing_field", "x"));
        List<String> v = roundtripViolations(c, SYNTH_RAW);
        assertEquals(3, v.size(), v.toString());
        assertTrue(v.stream().anyMatch(m -> m.startsWith("attempts")), v.toString());
        assertTrue(v.stream().anyMatch(m -> m.contains("dead_letter.reason")), v.toString());
        assertTrue(v.stream().anyMatch(m -> m.contains("dead_letter.missing_field")), v.toString());
    }

    @Test
    void runnerReportsForbiddenKeyViolationsOnSyntheticCase() {
        String raw = SYNTH_RAW.replace("\"attempts\":0", "\"attempts\":0,\"timestamp\":5");
        Map<String, Object> ok = synthetic(
            "expect", "warn", "forbidden_key", "/timestamp",
            "expect_absent_after_reencode", List.of("/timestamp"),
            "expect_preserved", Map.of("/job", "urn:acme:a"));
        assertEquals(List.of(), forbiddenKeyViolations(ok, raw));

        // /job is re-emitted, /job has another value, and no warning names /meta/ts.
        Map<String, Object> bad = synthetic(
            "expect", "warn", "forbidden_key", "/meta/ts",
            "expect_absent_after_reencode", List.of("/job"),
            "expect_preserved", Map.of("/job", "urn:acme:other"));
        List<String> v = forbiddenKeyViolations(bad, raw);
        assertEquals(3, v.size(), v.toString());
    }

    // --- helpers -------------------------------------------------------------------

    private static Map<String, Object> section(String key) {
        Object section = MANIFEST.get(key);
        assertNotNull(section, "manifest is missing the mandatory '" + key + "' section");
        return (Map<String, Object>) section;
    }

    private static List<Map<String, Object>> cases(String key) {
        List<Object> raw = (List<Object>) section(key).get("cases");
        assertNotNull(raw, key + ".cases");
        assertFalse(raw.isEmpty(), key + ".cases must not be empty");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    private static String name(Map<String, Object> c) {
        return String.valueOf(c.get("name"));
    }

    private static String fixture(Map<String, Object> c) {
        return readResource("/conformance/" + c.get("file"));
    }

    /** RFC 6901 JSON pointer resolution; returns {@link #ABSENT} when the path does not exist. */
    static Object resolve(Object doc, String pointer) {
        if (pointer.isEmpty()) {
            return doc;
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("invalid JSON pointer: " + pointer);
        }
        Object current = doc;
        for (String rawToken : pointer.substring(1).split("/", -1)) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    return ABSENT;
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                if (!token.matches("0|[1-9][0-9]*")) {
                    return ABSENT;
                }
                int index = Integer.parseInt(token);
                if (index >= list.size()) {
                    return ABSENT;
                }
                current = list.get(index);
            } else {
                return ABSENT;
            }
        }
        return current;
    }

    /** The raw JSON text of a top-level member of {@code json} (string-aware bracket scan). */
    /** Drop JSON whitespace outside string literals only (RFC 8259 insignificant whitespace). */
    private static String stripInsignificantWhitespace(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                out.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
            } else if (ch == '"') {
                inString = true;
                out.append(ch);
            } else if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String rawMember(String json, String member) {
        String needle = "\"" + member + "\":";
        int start = json.indexOf(needle);
        assertTrue(start >= 0, "member " + member + " not found in " + json);
        int i = start + needle.length();
        while (Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int begin = i;
        int depth = 0;
        boolean inString = false;
        for (; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(begin, i + 1);
                }
            } else if (depth == 0 && ch == ',') {
                return json.substring(begin, i).strip();
            }
        }
        return json.substring(begin).strip();
    }

    private static String readResource(String path) {
        try (InputStream in = BehaviourConformanceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
