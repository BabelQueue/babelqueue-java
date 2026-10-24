package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the core-scope {@code poison_dlq} conformance section (ADR-0035) on {@link PoisonRecord}: a strict JSON object is
 * spliced byte-for-byte, anything else is the {@code raw} wrapper (base64 plus {@code raw_encoding} for bytes that are
 * not valid UTF-8, never U+FFFD substitution).
 */
class PoisonDlqConformanceTest {

    private static final String ORIGINAL_QUEUE = "orders";
    private static final String DEAD_LETTER = "{\"reason\":\"poison\",\"original_queue\":\"" + ORIGINAL_QUEUE + "\"}";
    private static final String SPLICE_TAIL = ",\"dead_letter\":" + DEAD_LETTER + "}";

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> poisonDlqCases() {
        Map<String, Object> section = (Map<String, Object>) ConformanceResults.manifest().get("poison_dlq");
        assertEquals(ORIGINAL_QUEUE, section.get("original_queue"));
        List<DynamicTest> tests = new ArrayList<>();
        for (Object o : (List<Object>) section.get("cases")) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = (String) c.get("name");
            tests.add(dynamicTest(name, ConformanceResults.record("poison_dlq", name, () -> run(c))));
        }
        return tests.stream();
    }

    private static byte[] bodyOf(Map<String, Object> c) {
        if (c.containsKey("body_base64")) {
            return Base64.getDecoder().decode((String) c.get("body_base64"));
        }
        return ((String) c.get("body")).getBytes(StandardCharsets.UTF_8);
    }

    private static String trimJsonSpace(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && " \t\r\n".indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && " \t\r\n".indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    @SuppressWarnings("unchecked")
    private static void run(Map<String, Object> c) {
        String name = (String) c.get("name");
        Map<String, Object> expect = (Map<String, Object>) c.get("expect");
        String shape = (String) expect.get("shape");
        Map<String, Object> want = (Map<String, Object>) expect.get("record");
        byte[] body = bodyOf(c);
        String record = PoisonRecord.build(body, DEAD_LETTER);
        assertFalse(record.indexOf('\uFFFD') >= 0, name + ": the record holds U+FFFD");
        // Structural, not textual: the record is the wrapper when its distinct top-level members, minus the one
        // dead_letter, are exactly raw (+ raw_encoding) and the case expects the wrapper form.
        Set<String> keys = topLevelKeys(record);
        boolean wrapper = want.containsKey("raw") && keys.contains("raw");

        switch (shape) {
            case "wrapper" -> assertTrue(wrapper, name + ": expected the wrapper, got " + record);
            // A splice never invents a raw_encoding member and always carries exactly the dead_letter addition.
            case "splice" -> {
                assertFalse(keys.contains("raw_encoding"), name + ": expected a splice, got " + record);
                assertTrue(keys.contains("dead_letter"), name + ": the splice lost dead_letter: " + record);
            }
            case "splice_or_wrapper" -> { }
            default -> throw new AssertionError(name + ": unknown shape " + shape);
        }

        if (wrapper) {
            Map<String, Object> parsed = new java.util.LinkedHashMap<>((Map<String, Object>) Json.parse(record));
            Map<String, Object> deadLetter = (Map<String, Object>) parsed.remove("dead_letter");
            assertEquals("poison", deadLetter.get("reason"), name);
            assertEquals(ORIGINAL_QUEUE, deadLetter.get("original_queue"), name);
            assertEquals(want, parsed, name + ": " + record);
            return;
        }

        // Splice: the original text (outer whitespace trimmed) plus the dead_letter member, nothing re-encoded.
        String text = trimJsonSpace(new String(body, StandardCharsets.UTF_8));
        String inner = trimJsonSpace(text.substring(1, text.length() - 1));
        String expected = inner.isEmpty() ? "{" + SPLICE_TAIL.substring(1) : text.substring(0, text.length() - 1) + SPLICE_TAIL;
        assertEquals(expected, record, name);
        // (b) a splice has no top-level raw / raw_encoding member of its own making, and (c) its distinct top-level
        // member names, other than dead_letter, are the body's own (conformance splice_keys, as a set).
        assertFalse(keys.contains("raw_encoding"), name + ": a splice must not carry raw_encoding");
        if (expect.containsKey("splice_keys")) {
            Set<String> expectedKeys = new LinkedHashSet<>();
            for (Object k : (List<Object>) expect.get("splice_keys")) {
                expectedKeys.add((String) k);
            }
            Set<String> actualKeys = new LinkedHashSet<>(keys);
            actualKeys.remove("dead_letter");
            assertEquals(expectedKeys, actualKeys, name + ": top-level member names of " + record);
        }
        if (expect.containsKey("splice_literals")) {
            for (Object literal : (List<Object>) expect.get("splice_literals")) {
                assertTrue(record.contains((String) literal), name + ": missing literal " + literal);
            }
        } else {
            Map<String, Object> parsed = new java.util.LinkedHashMap<>((Map<String, Object>) Json.parse(record));
            parsed.remove("dead_letter");
            assertEquals(want, parsed, name + ": " + record);
        }
    }

    /**
     * The distinct top-level member names of a JSON object text, read with a tolerant scanner (string-aware, depth
     * tracking) because a conforming splice may hold values the test's own parser cannot represent.
     */
    private static Set<String> topLevelKeys(String json) {
        Set<String> keys = new LinkedHashSet<>();
        int depth = 0;
        int i = 0;
        int n = json.length();
        while (i < n) {
            char ch = json.charAt(i);
            if (ch == '"') {
                int start = i++;
                while (i < n && json.charAt(i) != '"') {
                    i += json.charAt(i) == '\\' ? 2 : 1;
                }
                int end = i++;
                if (depth == 1) {
                    int j = i;
                    while (j < n && " \t\r\n".indexOf(json.charAt(j)) >= 0) {
                        j++;
                    }
                    if (j < n && json.charAt(j) == ':') {
                        keys.add((String) Json.parse(json.substring(start, end + 1)));
                    }
                }
                continue;
            }
            if (ch == '{' || ch == '[') {
                depth++;
            } else if (ch == '}' || ch == ']') {
                depth--;
            }
            i++;
        }
        return keys;
    }

    @Test
    void topLevelKeysIgnoresNestedMembersAndStringContents() {
        String json = "{\"a\":{\"x\":1},\"b\":[{\"y\":2}],\"c\":\"{\\\"z\\\":1}\",\"a\":3,\"dead_letter\":{}}";
        assertEquals(Set.of("a", "b", "c", "dead_letter"), topLevelKeys(json));
    }

    @Test
    void invalidUtf8BecomesBase64NotReplacementCharacters() {
        String record = PoisonRecord.build(new byte[] {(byte) 0xFF, (byte) 0xFE, 'a'}, DEAD_LETTER);
        assertEquals("{\"raw\":\"//5h\",\"raw_encoding\":\"base64\"" + SPLICE_TAIL, record);
    }

    @Test
    void validUtf8TextHasNoRawEncoding() {
        String record = PoisonRecord.build("not json".getBytes(StandardCharsets.UTF_8), DEAD_LETTER);
        assertEquals("{\"raw\":\"not json\"" + SPLICE_TAIL, record);
    }

    @Test
    void rejectsADeadLetterThatIsNotASingleJsonObject() {
        byte[] body = {'{', '}'};
        for (String bad : new String[] {null, "", "[]", "1", "\"x\"", "null", "{", "{} {}", "{\"a\":1},\"b\":2", "{\"a\":1}x"}) {
            assertThrows(IllegalArgumentException.class, () -> PoisonRecord.build(body, bad), String.valueOf(bad));
        }
        // A well-formed object, including one with a nested dead_letter member of its own, is accepted.
        assertEquals("{\"dead_letter\":{\"dead_letter\":1}}", PoisonRecord.build(body, "{\"dead_letter\":1}"));
    }
}
