package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.babelqueue.schema.PayloadValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the behavioural sections of the vendored cross-SDK conformance manifest:
 * {@code roundtrip}, {@code data_shape}, {@code forbidden_keys} and
 * {@code payload_schema_unicode}. Every section is mandatory — a missing or empty
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
            Envelope decoded = EnvelopeCodec.decode(fixture(c));
            assertTrue(EnvelopeCodec.accepts(decoded), "decoded envelope must be accepted");

            Envelope retried = decoded.withAttempts(decoded.attempts() + 1);
            Object out = Json.parse(EnvelopeCodec.encode(retried));

            assertEquals(c.get("expect_attempts"), resolve(out, "/attempts"), "attempts");
            Map<String, Object> preserved = (Map<String, Object>) c.get("expect_preserved");
            assertNotNull(preserved, "expect_preserved");
            for (Map.Entry<String, Object> e : preserved.entrySet()) {
                Object actual = resolve(out, e.getKey());
                assertTrue(actual != ABSENT, "pointer " + e.getKey() + " missing after re-encode");
                assertEquals(e.getValue(), actual, "pointer " + e.getKey());
            }
        }));
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
            assertEquals("warn", c.get("expect"));
            String forbiddenKey = (String) c.get("forbidden_key");

            List<String> warnings = new ArrayList<>();
            Envelope decoded = EnvelopeCodec.decode(fixture(c), warnings::add);

            assertTrue(EnvelopeCodec.accepts(decoded), "forbidden key must not reject the message");
            assertTrue(
                warnings.stream().anyMatch(w -> w.contains(forbiddenKey)),
                "expected a warning naming " + forbiddenKey + ", got " + warnings);

            Object out = Json.parse(EnvelopeCodec.encode(decoded));
            for (Object pointer : (List<Object>) c.get("expect_absent_after_reencode")) {
                assertTrue(resolve(out, (String) pointer) == ABSENT, pointer + " must not be re-emitted");
            }
        }));
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
