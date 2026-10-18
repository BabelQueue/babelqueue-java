package com.babelqueue.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.babelqueue.ConformanceResults;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the {@code traceparent} section of the vendored conformance manifest (scope {@code otel},
 * ADR-0028 / ADR-0032): the consumer-side extraction must yield the header's trace id, parent
 * span id and flags for a well-formed {@code traceparent}, and fall back to the envelope
 * {@code trace_id} (ADR-0025) for an absent or malformed one. It lives in this package because
 * {@link Traceparent} is package-private.
 */
class TraceparentConformanceTest {

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> traceparentCases() {
        Map<String, Object> section = (Map<String, Object>) ConformanceResults.manifest().get("traceparent");
        assertNotNull(section, "manifest is missing the 'traceparent' section");
        List<Object> cases = (List<Object>) section.get("cases");
        assertFalse(cases.isEmpty(), "traceparent.cases must not be empty");

        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> c = (Map<String, Object>) cases.get(i);
            String id = c.get("name") instanceof String s ? s : "traceparent#" + i;
            tests.add(dynamicTest(id, ConformanceResults.record("traceparent", id, () -> run(c))));
        }
        return tests.stream();
    }

    @SuppressWarnings("unchecked")
    private static void run(Map<String, Object> c) {
        assertEquals("extract", c.get("mode"), "unknown traceparent mode");
        Map<String, String> headers = new java.util.HashMap<>();
        if (c.get("traceparent") != null) {
            headers.put(Traceparent.HEADER_TRACEPARENT, (String) c.get("traceparent"));
        }
        Map<String, Object> expect = (Map<String, Object>) c.get("expect");

        // A malformed header is ignored (never raises) and behaves exactly like no header.
        Context remote = Traceparent.remoteParentFromHeaders(headers);
        if ("traceparent".equals(expect.get("source"))) {
            assertNotNull(remote, "a well-formed traceparent must be extracted");
            SpanContext sc = Span.fromContext(remote).getSpanContext();
            assertEquals(expect.get("trace_id_hex"), sc.getTraceId());
            assertEquals(expect.get("parent_span_id"), sc.getSpanId());
            assertEquals(expect.get("flags"), sc.getTraceFlags().asHex());
        } else {
            assertEquals("trace_id", expect.get("source"));
            assertEquals(null, remote, "an absent or malformed traceparent must be ignored");
            assertEquals(expect.get("trace_id_hex"), Tracing.traceIdOf((String) c.get("trace_id")));
        }
    }
}
