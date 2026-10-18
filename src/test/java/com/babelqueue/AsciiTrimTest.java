package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-0032 section 5: trim and "blank" use the closed ASCII set (space, tab, LF, VT, FF, CR).
 * Unicode spaces such as U+00A0 and U+2003 are ordinary characters and survive.
 */
class AsciiTrimTest {

    private static final String NBSP = " ";
    private static final String EM_SPACE = " ";

    @Test
    void blankIsTheClosedAsciiSetOnly() {
        assertTrue(EnvelopeCodec.isBlankAscii(null));
        assertTrue(EnvelopeCodec.isBlankAscii(""));
        assertTrue(EnvelopeCodec.isBlankAscii(" \t\n\u000b\f\r"));
        assertFalse(EnvelopeCodec.isBlankAscii(NBSP));
        assertFalse(EnvelopeCodec.isBlankAscii(EM_SPACE));
        assertFalse(EnvelopeCodec.isBlankAscii(" " + NBSP + " "));
    }

    @Test
    void stripAsciiKeepsUnicodeSpaces() {
        assertEquals("urn:a", EnvelopeCodec.stripAscii(" \t\u000burn:a\f\r\n "));
        assertEquals(NBSP + "urn:a" + NBSP, EnvelopeCodec.stripAscii(" " + NBSP + "urn:a" + NBSP + " "));
        assertEquals(EM_SPACE, EnvelopeCodec.stripAscii(EM_SPACE));
    }

    @Test
    void makePreservesNoBreakSpaceInUrnAndTraceId() {
        Envelope env = EnvelopeCodec.make(" " + NBSP + "urn:a" + NBSP + " ", Map.of(), "q", NBSP + "trace" + NBSP);

        assertEquals(NBSP + "urn:a" + NBSP, env.job());
        assertEquals(NBSP + "trace" + NBSP, env.traceId());
    }

    @Test
    void makeRejectsAUrnThatIsOnlyAsciiWhitespaceButNotOneThatIsOnlyNbsp() {
        assertThrows(BabelQueueException.class, () -> EnvelopeCodec.make(" \t\r\n", Map.of()));
        assertEquals(NBSP, EnvelopeCodec.make(NBSP, Map.of()).job());
    }

    @Test
    void acceptsTreatsNbspTraceIdAsPresent() {
        Envelope env = EnvelopeCodec.make("urn:a", Map.of(), "q", NBSP);
        assertTrue(EnvelopeCodec.accepts(env));
    }

    // The trim is a linear index scan, never a regex: a large run of whitespace must not turn
    // into quadratic work (the Node port had a backtracking trim).
    private static final int HUGE = 200_000;

    @Test
    void trimIsLinearOnHugeWhitespaceRuns() {
        String interior = "a" + " ".repeat(HUGE) + "a";
        String padded = " ".repeat(HUGE) + "a" + "\t".repeat(HUGE);
        String allBlank = " \t\r\n".repeat(HUGE / 4);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertEquals(interior, EnvelopeCodec.stripAscii(interior));
            assertEquals("a", EnvelopeCodec.stripAscii(padded));
            assertEquals("", EnvelopeCodec.stripAscii(allBlank));
            assertFalse(EnvelopeCodec.isBlankAscii(interior));
            assertFalse(EnvelopeCodec.isBlankAscii(padded));
            assertTrue(EnvelopeCodec.isBlankAscii(allBlank));
            assertEquals(interior, EnvelopeCodec.make(interior, Map.of()).job());
            assertEquals("a", EnvelopeCodec.make(padded, Map.of()).job());
        });
    }
}
