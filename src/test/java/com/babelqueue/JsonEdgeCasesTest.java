package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises the hand-rolled JSON codec's error/escape/number paths and the exceptions. */
class JsonEdgeCasesTest {

    // ---- writer ----------------------------------------------------------------

    @Test
    void writesArraysAndNestedStructures() {
        assertEquals("[1,2,3]", Json.write(List.of(1, 2, 3)));
        assertEquals("{\"a\":[true,null]}", Json.write(Map.of("a", Arrays.asList(true, null))));
    }

    @Test
    void writeRejectsNonFiniteNumbers() {
        assertThrows(BabelQueueException.class, () -> Json.write(Double.NaN));
        assertThrows(BabelQueueException.class, () -> Json.write(Double.POSITIVE_INFINITY));
    }

    @Test
    void writeFallsBackToToStringForUnknownTypes() {
        Object weird = new Object() {
            @Override
            public String toString() {
                return "X";
            }
        };
        assertEquals("\"X\"", Json.write(weird));
    }

    @Test
    void writeEscapesAllControlCharacters() {
        assertEquals("\"\\b\\f\\n\\r\\t\"", Json.write("\b\f\n\r\t"));
        assertEquals("\"\\u0001\"", Json.write(String.valueOf((char) 1)));
    }

    @Test
    void writesIntegersDoublesAndBigIntegers() {
        assertEquals("42", Json.write(42L));
        assertEquals("3.5", Json.write(3.5));
        assertEquals(
                "123456789012345678901234567890",
                Json.write(new BigInteger("123456789012345678901234567890")));
    }

    // ---- parser: strings / escapes --------------------------------------------

    @Test
    void parsesAllStringEscapes() {
        assertEquals("\"", Json.parse("\"\\\"\""));
        assertEquals("/", Json.parse("\"\\/\""));
        assertEquals("\b\f\n\r\t", Json.parse("\"\\b\\f\\n\\r\\t\""));
        assertEquals("A", Json.parse("\"\\u0041\""));
    }

    @Test
    void rejectsInvalidAndTruncatedEscapes() {
        assertThrows(BabelQueueException.class, () -> Json.parse("\"\\x\""));
        assertThrows(BabelQueueException.class, () -> Json.parse("\"\\u00\""));
        assertThrows(BabelQueueException.class, () -> Json.parse("\"abc"));
    }

    // ---- parser: numbers -------------------------------------------------------

    @Test
    void parsesNumberForms() {
        assertEquals(-7L, Json.parse("-7"));
        assertEquals(3.14, Json.parse("3.14"));
        assertEquals(1.5e3, Json.parse("1.5E+3"));
        assertEquals(2.0e-2, Json.parse("2e-2"));
        assertEquals(
                new BigInteger("123456789012345678901234567890"),
                Json.parse("123456789012345678901234567890"));
    }

    // ---- parser: literals ------------------------------------------------------

    @Test
    void parsesBooleansAndNull() {
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
    }

    @Test
    void rejectsBadLiterals() {
        assertThrows(BabelQueueException.class, () -> Json.parse("tru"));
        assertThrows(BabelQueueException.class, () -> Json.parse("nul"));
    }

    // ---- parser: structure -----------------------------------------------------

    @Test
    void parsesEmptyObjectAndArray() {
        assertEquals(Map.of(), Json.parse("{}"));
        assertEquals(List.of(), Json.parse("[]"));
    }

    @Test
    void rejectsObjectStructureErrors() {
        assertThrows(BabelQueueException.class, () -> Json.parse("{1:2}"));
        assertThrows(BabelQueueException.class, () -> Json.parse("{\"a\" 1}"));
        assertThrows(BabelQueueException.class, () -> Json.parse("{\"a\":1 \"b\":2}"));
    }

    @Test
    void rejectsArrayStructureErrors() {
        assertThrows(BabelQueueException.class, () -> Json.parse("[1 2]"));
    }

    @Test
    void rejectsTrailingContentUnexpectedCharAndEnd() {
        assertThrows(BabelQueueException.class, () -> Json.parse("1 2"));
        assertThrows(BabelQueueException.class, () -> Json.parse("@"));
        assertThrows(BabelQueueException.class, () -> Json.parse(""));
    }

    // ---- exceptions ------------------------------------------------------------

    @Test
    void exceptionConstructors() {
        UnknownUrnException u = new UnknownUrnException("urn:babel:x:y");
        assertTrue(u.getMessage().contains("urn:babel:x:y"));

        Throwable cause = new IllegalStateException("boom");
        BabelQueueException e = new BabelQueueException("wrapped", cause);
        assertSame(cause, e.getCause());
    }
}
