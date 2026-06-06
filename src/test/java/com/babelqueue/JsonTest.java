package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesScalarsWithCorrectTypes() {
        assertEquals(Long.valueOf(7L), Json.parse("7"));
        assertEquals(Double.valueOf(0.5), Json.parse("0.5"));
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertNull(Json.parse("null"));
        assertEquals("hello", Json.parse("\"hello\""));
        assertInstanceOf(java.math.BigInteger.class, Json.parse("123456789012345678901234567890"));
    }

    @Test
    void parsesBackslashAndUnicodeEscapes() {
        // JSON "App\\Exceptions\\GatewayTimeout" -> App\Exceptions\GatewayTimeout
        assertEquals("App\\Exceptions\\GatewayTimeout",
            Json.parse("\"App\\\\Exceptions\\\\GatewayTimeout\""));
        assertEquals("A\tB\nC", Json.parse("\"A\\tB\\nC\""));
        assertEquals("☕ café", Json.parse("\"\\u2615 café\""));
    }

    @Test
    void parsesNestedObjectsAndArraysPreservingOrder() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse(
            "{\"b\":1,\"a\":[true,null,\"x\"],\"c\":{\"d\":2}}");

        assertEquals(List.of("b", "a", "c"), List.copyOf(m.keySet()));
        assertEquals(Long.valueOf(1L), m.get("b"));

        @SuppressWarnings("unchecked")
        List<Object> a = (List<Object>) m.get("a");
        assertEquals(3, a.size());
        assertEquals(Boolean.TRUE, a.get(0));
        assertNull(a.get(1));
        assertEquals("x", a.get(2));

        @SuppressWarnings("unchecked")
        Map<String, Object> c = (Map<String, Object>) m.get("c");
        assertEquals(Long.valueOf(2L), c.get("d"));
    }

    @Test
    void writesCompactUnescapedJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a/b <x> & café ☕");
        m.put("n", 0.5);
        m.put("i", 7L);
        m.put("b", true);
        m.put("z", null);

        String json = Json.write(m);
        assertEquals("{\"s\":\"a/b <x> & café ☕\",\"n\":0.5,\"i\":7,\"b\":true,\"z\":null}", json);
        // slashes, <, >, & and non-ASCII must NOT be escaped
        assertTrue(json.contains("a/b <x> & café ☕"));
    }

    @Test
    void writeEscapesQuotesBackslashesAndControlChars() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", Json.write("a\"b\\c\nd"));
    }

    @Test
    void parseRejectsTrailingContent() {
        assertThrows(BabelQueueException.class, () -> Json.parse("{} junk"));
    }
}
