package com.babelqueue.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.babelqueue.ConformanceResults;
import com.babelqueue.DeadLetterEvent;
import com.babelqueue.DeadLetterObserver;
import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import com.babelqueue.Meta;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the {@code span-shape}, {@code trace-id} and {@code metrics} sections of the vendored
 * conformance manifest (scope {@code otel}; ADR-0038, ADR-0039, ADR-0032) against
 * {@link Tracing}, {@link Tracing.Options} and {@link Metrics}, with in-memory span and metric
 * exporters. Lives in this package because the runner builds the SDK's wrappers directly.
 */
class OtelConformanceTest {

    /** The ADR-0038 section 4 table keys, including the deprecated transition keys. */
    private static final Set<String> TABLE_KEYS = Set.of(
        "messaging.system", "messaging.operation.type", "messaging.operation.name",
        "messaging.destination.name", "messaging.message.id", "messaging.message.conversation_id",
        "messaging.consumer.group.name", "babelqueue.urn", "babelqueue.schema_version",
        "babelqueue.attempts", "error.type", "messaging.operation", "messaging.babelqueue.attempts");

    private static final List<String> INT_KEYS =
        List.of("babelqueue.schema_version", "babelqueue.attempts", "messaging.babelqueue.attempts");

    // ---- span-shape ----------------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> spanShapeCases() {
        return section("span-shape", OtelConformanceTest::runSpanShape);
    }

    @SuppressWarnings("unchecked")
    private static void runSpanShape(Map<String, Object> c) throws Exception {
        assertEquals("span", c.get("mode"));
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        Tracer tracer = tracer(exporter);
        Tracing.Options options = Tracing.Options.create();
        if (c.get("system") instanceof String system) {
            options = options.system(system);
        }
        if (c.get("consumer_group") instanceof String group) {
            options = options.consumerGroup(group);
        }
        boolean send = "send".equals(c.get("operation"));
        boolean failing = "error".equals(c.get("outcome"));
        Envelope produced = null;

        if (send) {
            Envelope[] seen = {null};
            Map<String, Object> data = new HashMap<>();
            if (Boolean.FALSE.equals(c.get("envelope_built"))) {
                data = new FailingMap();
            }
            String queue = (String) c.get("queue");
            String urn = (String) c.get("urn");
            Map<String, Object> payload = data;
            try {
                Tracing.publish(tracer, urn, payload, queue, (HeaderSender) (envelope, headers) -> {
                    seen[0] = envelope;
                    if (failing) {
                        throw new IllegalStateException("transport rejected the message");
                    }
                }, options);
                assertFalse(failing, "the publish must fail");
            } catch (IllegalStateException | UnsupportedOperationException e) {
                assertTrue(failing, "unexpected failure: " + e);
            }
            produced = seen[0];
        } else {
            Envelope envelope = deliveredEnvelope(c);
            try {
                Tracing.wrapHandler(tracer, e -> {
                    if (failing) {
                        throw new IllegalStateException("handler failed");
                    }
                }, null, options).handle(envelope);
                assertFalse(failing, "the handler must fail");
            } catch (IllegalStateException e) {
                assertTrue(failing, "unexpected failure: " + e);
                assertEquals("handler failed", e.getMessage(), "the error is rethrown unchanged");
            }
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        Map<String, Object> expect = (Map<String, Object>) c.get("expect");

        // Placeholders from the produced envelope (the runner reads it, also when the publish fails).
        String producedQueue = produced == null ? null : produced.meta().queue();
        boolean producedQueueBlank = EnvelopeCodec.isBlankAscii(producedQueue);
        Map<String, Object> expected = new LinkedHashMap<>((Map<String, Object>) expect.get("attributes"));
        for (Map.Entry<String, Object> e : new ArrayList<>(expected.entrySet())) {
            if (e.getValue() instanceof String s && s.startsWith("$produced.")) {
                switch (s) {
                    case "$produced.meta.id" -> e.setValue(produced.meta().id());
                    case "$produced.trace_id" -> e.setValue(produced.traceId());
                    case "$produced.meta.queue" -> {
                        if (producedQueueBlank) {
                            expected.remove(e.getKey());
                        } else {
                            e.setValue(producedQueue);
                        }
                    }
                    default -> fail("unknown placeholder " + s);
                }
            }
        }

        // span name and kind
        if (expect.get("span_name_one_of") instanceof List<?> names) {
            assertTrue(names.contains(span.getName()), "span name " + span.getName() + " not in " + names);
        } else {
            String name = (String) expect.get("span_name");
            if (name.contains("$produced.meta.queue")) {
                name = producedQueueBlank ? name.replace(" $produced.meta.queue", "")
                    : name.replace("$produced.meta.queue", producedQueue);
            }
            assertEquals(name, span.getName());
        }
        assertEquals(SpanKind.valueOf((String) expect.get("span_kind")), span.getKind());
        assertFalse(span.getName().contains((String) c.get("urn")), "the URN is never in the span name");

        // status and error.type
        boolean errorExpected = "error".equals(expect.get("status"));
        assertEquals(errorExpected ? StatusCode.ERROR : StatusCode.UNSET, span.getStatus().getStatusCode());
        String errorType = span.getAttributes().get(AttributeKey.stringKey("error.type"));
        if (errorExpected) {
            assertEquals("present", expect.get("error_type"));
            assertTrue(errorType != null && !errorType.isEmpty(), "error.type must be present and non-empty");
            assertFalse(span.getEvents().isEmpty(), "the error must be recorded as an event");
        } else {
            assertNull(errorType, "error.type must be absent on success");
        }

        // attributes
        Set<String> optional = new HashSet<>();
        if (expect.get("optional_attributes") instanceof List<?> opt) {
            opt.forEach(o -> optional.add((String) o));
        }
        Map<String, Object> actual = new HashMap<>();
        span.getAttributes().forEach((k, v) -> actual.put(k.getKey(), v));
        for (Map.Entry<String, Object> e : expected.entrySet()) {
            Object want = INT_KEYS.contains(e.getKey()) ? Long.valueOf(((Number) e.getValue()).longValue()) : e.getValue();
            assertEquals(want, actual.get(e.getKey()), "attribute " + e.getKey());
        }
        for (String key : actual.keySet()) {
            if ("error.type".equals(key)) {
                continue; // asserted above
            }
            if (TABLE_KEYS.contains(key)) {
                assertTrue(expected.containsKey(key) || optional.contains(key), "unexpected table attribute " + key);
            } else {
                assertFalse(key.startsWith("babelqueue."), "forbidden babelqueue.* attribute " + key);
            }
        }
        for (String key : optional) {
            if (actual.containsKey(key)) {
                assertEquals(c.get("queue"), actual.get(key), "optional " + key + " must equal the given queue");
                assertEquals("send " + c.get("queue"), span.getName(), "name must agree with the destination");
            } else {
                assertEquals("send", span.getName(), "name must agree with the missing destination");
            }
        }
    }

    private static Envelope deliveredEnvelope(Map<String, Object> c) {
        Meta meta = new Meta(
            (String) c.get("message_id"), (String) c.get("queue"), "java",
            ((Number) c.get("schema_version")).intValue(), 1_700_000_000_000L);
        return new Envelope(
            (String) c.get("urn"), (String) c.get("trace_id"), new LinkedHashMap<>(), meta,
            ((Number) c.get("attempts")).intValue(), null);
    }

    /** A {@code data} map whose copy throws, so {@code EnvelopeCodec.make} fails before an envelope exists. */
    private static final class FailingMap extends AbstractMap<String, Object> {
        @Override
        public java.util.Set<Entry<String, Object>> entrySet() {
            throw new UnsupportedOperationException("data cannot be encoded");
        }
    }

    // ---- trace-id ------------------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> traceIdCases() {
        return section("trace-id", OtelConformanceTest::runTraceId);
    }

    @SuppressWarnings("unchecked")
    private static void runTraceId(Map<String, Object> c) throws Exception {
        String mode = (String) c.get("mode");
        Map<String, Object> expect = (Map<String, Object>) c.get("expect");
        String traceId = (String) c.get("trace_id");
        switch (mode) {
            case "map" -> {
                String hex = Tracing.traceIdOf(traceId);
                assertEquals(expect.get("trace_id_hex"), hex);
                assertTrue(hex.matches("[0-9a-f]{32}"));
                if ("hash".equals(expect.get("mapping"))) {
                    assertEquals(sha256First16(traceId), hex, "hash = SHA-256 of the UTF-8 bytes, first 16");
                } else {
                    assertEquals(traceId.replace("-", "").toLowerCase(Locale.ROOT), hex, "decode = the UUID bytes");
                }
            }
            case "decode" -> {
                Envelope decoded = EnvelopeCodec.decode(rawEnvelope(traceId));
                assertEquals(expect.get("trace_id"), decoded.traceId());
            }
            case "reemit" -> {
                Envelope decoded = EnvelopeCodec.decode(rawEnvelope(traceId));
                String reemitted = EnvelopeCodec.encode(decoded.withAttempts(decoded.attempts() + 1));
                assertEquals(expect.get("trace_id"), EnvelopeCodec.decode(reemitted).traceId());
            }
            case "downstream" -> {
                Envelope inbound = EnvelopeCodec.decode(rawEnvelope(traceId));
                Envelope out = EnvelopeCodec.make("urn:acme:next", Map.of(), "orders", inbound.traceId());
                assertEquals(expect.get("trace_id"), out.traceId());
            }
            case "make" -> {
                Envelope made = EnvelopeCodec.make((String) c.get("urn"), Map.of(), "orders", traceId);
                assertEquals(expect.get("trace_id"), made.traceId());
                assertEquals(expect.get("trace_id_hex"), Tracing.traceIdOf(made.traceId()));
            }
            default -> fail("unknown trace-id mode " + mode);
        }
    }

    private static String rawEnvelope(String traceId) {
        return "{\"job\":\"urn:acme:a\",\"trace_id\":" + jsonString(traceId) + ",\"data\":{},"
            + "\"meta\":{\"id\":\"0b6f3e1c-5d2a-4c7e-9a41-8f3d2b6c1e90\",\"queue\":\"orders\",\"lang\":\"go\","
            + "\"schema_version\":1,\"created_at\":1700000000000},\"attempts\":0}";
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        b.append(String.format("\\u%04x", (int) ch));
                    } else {
                        b.append(ch);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private static String sha256First16(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            b.append(String.format("%02x", d[i]));
        }
        return b.toString();
    }

    // ---- metrics -------------------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> metricsCases() {
        return section("metrics", OtelConformanceTest::runMetrics);
    }

    @SuppressWarnings("unchecked")
    private static void runMetrics(Map<String, Object> c) throws Exception {
        String mode = (String) c.get("mode");
        Map<String, Object> expect = (Map<String, Object>) c.get("expect");
        switch (mode) {
            case "instrument" -> runInstrument(expect);
            case "consume" -> runRecords(consumeScenario(c), expect, null);
            case "publish" -> {
                Envelope[] seen = {null};
                runRecords(publishScenario(c, seen), expect, seen);
            }
            case "dead_letter" -> runDeadLetter(c, expect);
            default -> fail("unknown metrics mode " + mode);
        }
    }

    /** Drives the consumer wrapper; returns the collected metrics. */
    private static List<MetricData> consumeScenario(Map<String, Object> c) throws Exception {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        Tracing.Options options = Tracing.Options.create();
        if (!Boolean.FALSE.equals(c.get("meter"))) {
            options = options.meter(SdkMeterProvider.builder().registerMetricReader(reader).build().get("test"));
        }
        if (c.get("system") instanceof String system) {
            options = options.system(system);
        }
        boolean failing = "error".equals(c.get("outcome"));
        Meta meta = new Meta("0b6f3e1c-5d2a-4c7e-9a41-8f3d2b6c1e90", (String) c.get("queue"), "java", 1, 1L);
        Envelope envelope = new Envelope(
            "urn:acme:order.created", "7b3f9c2a-e41d-4f88-9b2a-1c0d5e6f7a8b", new LinkedHashMap<>(), meta,
            ((Number) c.get("attempts")).intValue(), null);
        try {
            Tracing.wrapHandler(tracer(InMemorySpanExporter.create()), e -> {
                if (failing) {
                    throw new IllegalStateException("handler failed");
                }
            }, null, options).handle(envelope);
            assertFalse(failing);
        } catch (IllegalStateException e) {
            assertTrue(failing);
        }
        return new ArrayList<>(reader.collectAllMetrics());
    }

    /** Drives the producer wrapper; returns the collected metrics. */
    private static List<MetricData> publishScenario(Map<String, Object> c, Envelope[] seen) throws Exception {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        Tracing.Options options = Tracing.Options.create();
        if (!Boolean.FALSE.equals(c.get("meter"))) {
            options = options.meter(SdkMeterProvider.builder().registerMetricReader(reader).build().get("test"));
        }
        if (c.get("system") instanceof String system) {
            options = options.system(system);
        }
        boolean failing = "error".equals(c.get("outcome"));
        try {
            Tracing.publish(tracer(InMemorySpanExporter.create()), "urn:acme:order.created", Map.of("a", 1),
                (String) c.get("queue"), (HeaderSender) (envelope, headers) -> {
                    seen[0] = envelope;
                    if (failing) {
                        throw new IllegalStateException("transport failed");
                    }
                }, options);
            assertFalse(failing);
        } catch (IllegalStateException e) {
            assertTrue(failing);
        }
        return new ArrayList<>(reader.collectAllMetrics());
    }

    @SuppressWarnings("unchecked")
    private static void runDeadLetter(Map<String, Object> c, Map<String, Object> expect) {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        Metrics metrics = Metrics.create(SdkMeterProvider.builder().registerMetricReader(reader).build().get("test"));
        DeadLetterObserver observer = metrics.deadLetterObserver((String) c.get("system"));
        boolean called = Boolean.TRUE.equals(c.get("observer_called"));
        assertEquals(called, expect.get("observer_called"));
        if (called) {
            Map<String, Object> ev = (Map<String, Object>) c.get("event");
            DeadLetterObserver.notifySafely(observer, new DeadLetterEvent(
                (String) ev.get("queue"), (String) ev.get("dead_letter_queue"), (String) ev.get("reason")));
        }
        // observer_called: false cases carry sdk_test_required: the core runs no consume loop, so
        // no event is delivered here; the transport tests (Phases 16/17) assert "not called".
        compareRecords(new ArrayList<>(reader.collectAllMetrics()), (List<Map<String, Object>>) expect.get("records"), null);
    }

    @SuppressWarnings("unchecked")
    private static void runRecords(List<MetricData> collected, Map<String, Object> expect, Envelope[] produced) {
        compareRecords(collected, (List<Map<String, Object>>) expect.get("records"), produced);
    }

    /** A recorded data point flattened to a canonical, comparable string. */
    private static void compareRecords(List<MetricData> collected, List<Map<String, Object>> want, Envelope[] produced) {
        List<String> actual = new ArrayList<>();
        for (MetricData m : collected) {
            Collection<?> points = m.getType() == MetricDataType.HISTOGRAM
                ? m.getHistogramData().getPoints() : m.getLongSumData().getPoints();
            for (Object p : points) {
                if (p instanceof HistogramPointData h) {
                    assertEquals(1L, h.getCount(), "one observation per invocation");
                    assertTrue(Double.isFinite(h.getSum()) && h.getSum() >= 0, "non-negative seconds");
                    actual.add(canonical(m.getName(), "record", "non_negative_seconds", h.getAttributes()));
                } else {
                    LongPointData l = (LongPointData) p;
                    actual.add(canonical(m.getName(), "add", String.valueOf(l.getValue()), l.getAttributes()));
                }
            }
        }
        List<String> expected = new ArrayList<>();
        for (Map<String, Object> r : want) {
            Map<String, String> attrs = new LinkedHashMap<>();
            ((Map<String, Object>) r.get("attributes")).forEach((k, v) -> {
                if (v instanceof String s && "$produced.meta.queue".equals(s)) {
                    String q = produced[0].meta().queue();
                    if (!EnvelopeCodec.isBlankAscii(q)) {
                        attrs.put(k, q);
                    }
                } else {
                    attrs.put(k, (String) v);
                }
            });
            if ("present".equals(r.get("error_type"))) {
                attrs.put("error.type", "<present>");
            }
            expected.add(canonical((String) r.get("metric"), (String) r.get("kind"), String.valueOf(r.get("value")), attrs));
        }
        // error.type values are language-specific: normalise a present, non-empty value.
        List<String> normalised = new ArrayList<>();
        for (String a : actual) {
            normalised.add(a.replaceAll("error\\.type=[^;]+;", "error.type=<present>;"));
        }
        Collections.sort(normalised);
        Collections.sort(expected);
        assertEquals(expected, normalised);
    }

    private static String canonical(String metric, String kind, String value, Attributes attributes) {
        Map<String, String> m = new LinkedHashMap<>();
        attributes.forEach((k, v) -> m.put(k.getKey(), String.valueOf(v)));
        return canonical(metric, kind, value, m);
    }

    private static String canonical(String metric, String kind, String value, Map<String, String> attributes) {
        StringBuilder b = new StringBuilder(metric).append('|').append(kind).append('|').append(value).append('|');
        new java.util.TreeMap<>(attributes).forEach((k, v) -> b.append(k).append('=').append(v).append(';'));
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void runInstrument(Map<String, Object> expect) {
        String metric = (String) expect.get("metric");
        Map<String, Object> attrs = (Map<String, Object>) expect.get("attributes");
        List<String> always = (List<String>) attrs.get("always");
        List<String> withDestination = (List<String>) attrs.get("when_destination_known");
        List<String> onFailure = (List<String>) attrs.getOrDefault("on_failure", List.of());

        InMemoryMetricReader reader = InMemoryMetricReader.create();
        Tracing.Options options = Tracing.Options.create()
            .system("kafka")
            .meter(SdkMeterProvider.builder().registerMetricReader(reader).build().get("test"));
        Tracer tracer = tracer(InMemorySpanExporter.create());
        Meta meta = new Meta("id-1", "orders", "java", 1, 1L);
        Envelope envelope = new Envelope("urn:acme:x", "t-1", new LinkedHashMap<>(), meta, 2, null);
        boolean failing = !onFailure.isEmpty();
        try {
            switch ((String) expect.get("recorded_by")) {
                case "consumer wrapper" -> Tracing.wrapHandler(tracer, e -> {
                    if (failing) {
                        throw new IllegalStateException("x");
                    }
                }, null, options).handle(envelope);
                case "producer wrapper" -> Tracing.publish(tracer, "urn:acme:x", Map.of(), "orders",
                    (HeaderSender) (e, h) -> {
                        if (failing) {
                            throw new IllegalStateException("x");
                        }
                    }, options);
                case "dead-letter observer" -> options.metrics().deadLetterObserver("kafka")
                    .onDeadLetter(new DeadLetterEvent("orders", "orders.dlq", "failed"));
                default -> fail("unknown recorder");
            }
        } catch (Exception expected) {
            assertTrue(failing);
        }

        MetricData data = reader.collectAllMetrics().stream()
            .filter(m -> m.getName().equals(metric)).findFirst()
            .orElseThrow(() -> new AssertionError("metric " + metric + " was not recorded"));
        assertEquals(expect.get("unit"), data.getUnit());
        Set<String> keys = new HashSet<>();
        if ("histogram".equals(expect.get("instrument"))) {
            assertEquals(MetricDataType.HISTOGRAM, data.getType());
            HistogramPointData p = data.getHistogramData().getPoints().iterator().next();
            p.getAttributes().forEach((k, v) -> keys.add(k.getKey()));
            List<Double> want = new ArrayList<>();
            ((List<Object>) expect.get("advisory_bucket_boundaries")).forEach(n -> want.add(((Number) n).doubleValue()));
            assertEquals(want, p.getBoundaries(), "advisory bucket boundaries");
        } else {
            assertEquals(MetricDataType.LONG_SUM, data.getType());
            assertTrue(data.getLongSumData().isMonotonic());
            data.getLongSumData().getPoints().iterator().next().getAttributes().forEach((k, v) -> keys.add(k.getKey()));
        }
        Set<String> wantKeys = new HashSet<>(always);
        wantKeys.addAll(withDestination);
        wantKeys.addAll(onFailure);
        assertEquals(wantKeys, keys, "attribute key set of " + metric);
        for (Object forbidden : (List<Object>) expect.get("forbidden_attributes")) {
            assertFalse(keys.contains(forbidden), "forbidden attribute " + forbidden);
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------

    private interface CaseBody {
        void run(Map<String, Object> c) throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static Stream<DynamicTest> section(String name, CaseBody body) {
        Map<String, Object> section = (Map<String, Object>) ConformanceResults.manifest().get(name);
        assertNotNull(section, "manifest is missing the '" + name + "' section");
        List<Object> cases = (List<Object>) section.get("cases");
        assertFalse(cases.isEmpty(), name + ".cases must not be empty");
        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Map<String, Object> c = (Map<String, Object>) cases.get(i);
            String id = c.get("name") instanceof String s ? s : name + "#" + i;
            tests.add(dynamicTest(id, ConformanceResults.record(name, id, () -> body.run(c))));
        }
        return tests.stream();
    }

    private static Tracer tracer(InMemorySpanExporter exporter) {
        return SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
            .tracerBuilder("conformance")
            .setSchemaUrl(Tracing.SCHEMA_URL)
            .build();
    }
}
