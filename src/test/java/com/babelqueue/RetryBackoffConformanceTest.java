package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Runs the {@code retry_backoff} conformance section (ADR-0034 section 9) on {@link RetryBackoff}. */
class RetryBackoffConformanceTest {

    @SuppressWarnings("unchecked")
    @TestFactory
    Stream<DynamicTest> retryBackoffCases() {
        Map<String, Object> section = (Map<String, Object>) ConformanceResults.manifest().get("retry_backoff");
        List<DynamicTest> tests = new ArrayList<>();
        for (Object o : (List<Object>) section.get("cases")) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = (String) c.get("name");
            tests.add(dynamicTest(name, ConformanceResults.record("retry_backoff", name, () -> run(c))));
        }
        return tests.stream();
    }

    @SuppressWarnings("unchecked")
    private static void run(Map<String, Object> c) {
        RetryBackoff backoff = RetryBackoff.of(
            ((Number) c.get("retry_backoff")).doubleValue(),
            ((Number) c.get("retry_backoff_multiplier")).doubleValue(),
            ((Number) c.get("retry_backoff_max")).doubleValue(),
            RetryBackoff.Jitter.parse((String) c.get("retry_jitter")));
        int n = ((Number) c.get("n")).intValue();

        if (c.containsKey("expect_delay_seconds")) {
            assertEquals(((Number) c.get("expect_delay_seconds")).doubleValue(), backoff.delaySeconds(n), 1e-9);
            return;
        }
        Map<String, Object> range = (Map<String, Object>) c.get("expect_delay_range_seconds");
        double min = ((Number) range.get("min")).doubleValue();
        double max = ((Number) range.get("max")).doubleValue();
        for (int i = 0; i < 200; i++) {
            double delay = backoff.delaySeconds(n);
            assertTrue(delay >= min && delay <= max, "delay " + delay + " outside [" + min + ", " + max + "]");
        }
    }

    @Test
    void defaultsAreZero() {
        assertEquals(0.0, RetryBackoff.none().delaySeconds(1));
        assertEquals(0.0, RetryBackoff.none().delaySeconds(50));
    }

    @Test
    void fullJitterUsesTheInjectedSource() {
        RetryBackoff b = RetryBackoff.of(4, 2, 0, RetryBackoff.Jitter.FULL, () -> 0.25);
        assertEquals(1.0, b.delaySeconds(1), 1e-9);
        assertEquals(2.0, b.delaySeconds(2), 1e-9);
    }

    @Test
    void invalidParametersAreRejected() {
        assertThrows(BabelQueueException.class, () -> RetryBackoff.of(-1, 1, 0, RetryBackoff.Jitter.NONE));
        assertThrows(BabelQueueException.class, () -> RetryBackoff.of(1, 0.5, 0, RetryBackoff.Jitter.NONE));
        assertThrows(BabelQueueException.class, () -> RetryBackoff.of(1, 1, -1, RetryBackoff.Jitter.NONE));
        assertThrows(BabelQueueException.class, () -> RetryBackoff.Jitter.parse("equal"));
        assertEquals(RetryBackoff.Jitter.FULL, RetryBackoff.Jitter.parse("FULL"));
    }

    @Test
    void zeroBaseStaysZeroEvenWhenThePowerOverflows() {
        assertEquals(0.0, RetryBackoff.of(0, 2, 60, RetryBackoff.Jitter.NONE).ceilingSeconds(1100));
        assertEquals(0.0, RetryBackoff.of(0, 2, 0, RetryBackoff.Jitter.NONE).ceilingSeconds(1100));
        assertEquals(0.0, RetryBackoff.of(0, 2, 60, RetryBackoff.Jitter.FULL, () -> 0.9).delaySeconds(1100));
        assertEquals(0.0, RetryBackoff.of(0, 10, 60, RetryBackoff.Jitter.NONE).ceilingSeconds(5));
    }

    @Test
    void hugeExponentDoesNotOverflowIntoNaN() {
        RetryBackoff b = RetryBackoff.of(1, 10, 0, RetryBackoff.Jitter.NONE);
        assertTrue(b.delaySeconds(5000) > 0);
        RetryBackoff capped = RetryBackoff.of(1, 10, 60, RetryBackoff.Jitter.NONE);
        assertEquals(60.0, capped.delaySeconds(5000), 1e-9);
    }
}
