package com.babelqueue.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelqueue.DeadLetterEvent;
import com.babelqueue.DeadLetterObserver;
import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TracingTest {

    private static final String TRACE_ID = "7b3f9c2a-e41d-4f88-9b2a-1c0d5e6f7a8b";
    private static final String INVALID = "00000000000000000000000000000000";

    private InMemorySpanExporter exporter;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        tracer = provider.get("test");
    }

    private SpanData onlySpan() {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        return spans.get(0);
    }

    @Test
    void traceIdRoundTripsAndHashesNonUuid() {
        String hex = Tracing.traceIdOf(TRACE_ID);
        assertTrue(hex.matches("[0-9a-f]{32}"));
        assertEquals(TRACE_ID, Tracing.uuidOf(hex));

        // a non-uuid maps deterministically to a valid, distinct trace id
        assertEquals(Tracing.traceIdOf("not-a-uuid"), Tracing.traceIdOf("not-a-uuid"));
        assertNotEquals(hex, Tracing.traceIdOf("not-a-uuid"));
        assertTrue(Tracing.traceIdOf("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz").matches("[0-9a-f]{32}"));
        assertNotEquals(INVALID, Tracing.traceIdOf(null));
    }

    @Test
    void wrapHandlerEmitsConsumerSpanInTraceIdTrace() throws Exception {
        boolean[] called = {false};
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1), "orders", TRACE_ID);

        Tracing.wrapHandler(tracer, e -> called[0] = true).handle(env);

        assertTrue(called[0]);
        SpanData span = onlySpan();
        assertEquals("process orders", span.getName());
        assertEquals("urn:babel:orders:created", span.getAttributes().get(AttributeKey.stringKey("babelqueue.urn")));
        assertEquals("babelqueue", span.getAttributes().get(AttributeKey.stringKey("messaging.system")));
        assertEquals(0L, span.getAttributes().get(AttributeKey.longKey("babelqueue.attempts")));
        assertEquals(SpanKind.CONSUMER, span.getKind());
        assertEquals(Tracing.traceIdOf(TRACE_ID), span.getSpanContext().getTraceId());
        assertEquals(TRACE_ID, span.getAttributes().get(AttributeKey.stringKey("messaging.message.conversation_id")));
        assertEquals("orders", span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")));
        assertEquals(0L, span.getAttributes().get(AttributeKey.longKey("messaging.babelqueue.attempts")));
    }

    @Test
    void wrapHandlerRecordsErrorAndRethrows() {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of(), "orders", TRACE_ID);

        IllegalStateException boom = assertThrows(IllegalStateException.class, () ->
            Tracing.wrapHandler(tracer, e -> {
                throw new IllegalStateException("boom");
            }).handle(env));
        assertEquals("boom", boom.getMessage());

        SpanData span = onlySpan();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertFalse(span.getEvents().isEmpty());
    }

    @Test
    void publishEmitsProducerSpanAndStampsTraceId() throws Exception {
        Envelope[] sent = {null};

        String id = Tracing.publish(tracer, "urn:babel:orders:created", Map.of("order_id", 7), e -> sent[0] = e);

        SpanData span = onlySpan();
        assertEquals(SpanKind.PRODUCER, span.getKind());
        assertEquals("send default", span.getName());
        assertEquals("default", span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")));
        assertEquals("urn:babel:orders:created", span.getAttributes().get(AttributeKey.stringKey("babelqueue.urn")));
        assertEquals(id, span.getAttributes().get(AttributeKey.stringKey("messaging.message.id")));
        // the published trace_id encodes the producer span's trace, so a consumer recovers it
        assertEquals(Tracing.uuidOf(span.getSpanContext().getTraceId()), sent[0].traceId());
        assertEquals(span.getSpanContext().getTraceId(), Tracing.traceIdOf(sent[0].traceId()));
    }

    @Test
    void publishRecordsAFailingSend() {
        Exception boom = assertThrows(IllegalStateException.class, () ->
            Tracing.publish(tracer, "urn:babel:orders:created", Map.of(), e -> {
                throw new IllegalStateException("send failed");
            }));
        assertEquals("send failed", boom.getMessage());

        SpanData span = onlySpan();
        assertEquals(SpanKind.PRODUCER, span.getKind());
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
    }

    @Test
    void semconvPinIsExposed() {
        assertEquals("1.44.0", Tracing.SEMCONV_VERSION);
        assertEquals("https://opentelemetry.io/schemas/1.44.0", Tracing.SCHEMA_URL);
        assertEquals("babelqueue", Tracing.DEFAULT_SYSTEM);
    }

    @Test
    void consumerGroupAndSystemOptionsAreWrittenAndExistingSignaturesStillWork() throws Exception {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of(), "orders.events", TRACE_ID);
        Tracing.Options opts = Tracing.Options.create().system("kafka").consumerGroup("billing");

        Tracing.wrapHandler(tracer, e -> { }, null, opts).handle(env);

        SpanData span = onlySpan();
        assertEquals("process orders.events", span.getName());
        assertEquals("kafka", span.getAttributes().get(AttributeKey.stringKey("messaging.system")));
        assertEquals("billing", span.getAttributes().get(AttributeKey.stringKey("messaging.consumer.group.name")));
        assertEquals("kafka", opts.resolvedSystem());
        assertEquals("babelqueue", Tracing.Options.create().system("").resolvedSystem());
    }

    @Test
    void producerWithBlankQueueIsTheBareSendSpanWithNoDestination() throws Exception {
        Tracing.publish(tracer, "urn:babel:orders:created", Map.of(), "  ", e -> { }, null);

        SpanData span = onlySpan();
        assertEquals("send", span.getName());
        assertNull(span.getAttributes().get(AttributeKey.stringKey("messaging.destination.name")));
        assertEquals("urn:babel:orders:created", span.getAttributes().get(AttributeKey.stringKey("babelqueue.urn")));
    }

    @Test
    void failingSendStillCarriesTheProducedIdsAndErrorType() {
        assertThrows(IllegalStateException.class, () ->
            Tracing.publish(tracer, "urn:babel:orders:created", Map.of(), "orders", e -> {
                throw new IllegalStateException("send failed");
            }));

        SpanData span = onlySpan();
        assertEquals("java.lang.IllegalStateException", span.getAttributes().get(AttributeKey.stringKey("error.type")));
        assertTrue(span.getAttributes().get(AttributeKey.stringKey("messaging.message.id")) != null);
    }

    @Test
    void noMeterMeansNoMetricsAndOptionsAreImmutable() throws Exception {
        Tracing.Options base = Tracing.Options.create();
        Tracing.Options withMeter = base.meter(SdkMeterProvider.builder().build().get("t"));

        assertNull(base.metrics());
        assertTrue(withMeter.metrics() != null);
        assertNull(withMeter.meter(null).metrics());
        assertNull(withMeter.system("kafka").system("x").consumerGroup("g").meter(null).metrics());
    }

    @Test
    void metricsRecordWithoutUrnOrIdsAndDeadLetterObserverCounts() throws Exception {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        Tracing.Options opts = Tracing.Options.create()
            .system("redis")
            .meter(SdkMeterProvider.builder().registerMetricReader(reader).build().get("t"));
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of(), "orders", TRACE_ID).withAttempts(1);

        Tracing.wrapHandler(tracer, e -> { }, null, opts).handle(env);
        Tracing.publish(tracer, "urn:babel:orders:created", Map.of(), "orders", e -> { }, opts);
        opts.metrics().deadLetterObserver("redis").onDeadLetter(new DeadLetterEvent("orders", "orders.dlq", "failed"));

        List<String> names = reader.collectAllMetrics().stream().map(MetricData::getName).sorted().toList();
        assertEquals(List.of("babelqueue.dlq.messages", "babelqueue.retries", "messaging.client.consumed.messages",
            "messaging.client.sent.messages", "messaging.process.duration"), names);
    }

    @Test
    void deadLetterObserverIsolatesFailuresAndToleratesNull() {
        AtomicInteger calls = new AtomicInteger();
        DeadLetterEvent event = new DeadLetterEvent(null, "orders.dlq", null);
        assertEquals("", event.queue());
        assertEquals("", event.reason());

        DeadLetterObserver.notifySafely(null, event);
        DeadLetterObserver.notifySafely(e -> {
            calls.incrementAndGet();
            throw new IllegalStateException("observer blew up");
        }, event);

        assertEquals(1, calls.get());
    }

    @Test
    void producerUrnAttributeIsTheEnvelopeJobNotTheRawArgument() throws Exception {
        Envelope[] sent = {null};

        Tracing.publish(tracer, " urn:babel:orders:created\n", Map.of(), "orders", e -> sent[0] = e);

        SpanData span = onlySpan();
        assertEquals("urn:babel:orders:created", sent[0].job());
        assertEquals(sent[0].job(), span.getAttributes().get(AttributeKey.stringKey("babelqueue.urn")));
    }

    @Test
    void deadLetterObserverIsolatesErrorsButNotVirtualMachineErrors() {
        DeadLetterEvent event = new DeadLetterEvent("orders", "orders.dlq", "failed");
        AtomicInteger calls = new AtomicInteger();

        DeadLetterObserver.notifySafely(e -> {
            calls.incrementAndGet();
            throw new AssertionError("observer assertion");
        }, event);
        DeadLetterObserver.notifySafely(e -> {
            calls.incrementAndGet();
            throw new NoClassDefFoundError("missing");
        }, event);
        assertEquals(2, calls.get());

        assertThrows(OutOfMemoryError.class, () -> DeadLetterObserver.notifySafely(e -> {
            throw new OutOfMemoryError("oom");
        }, event));
    }

    @Test
    void nonUuidTraceIdWarnsOncePerValueAndSanitisesTheLogLine() {
        Logger logger = Logger.getLogger(Tracing.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                records.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(capture);
        try {
            // a UUID is decoded, never hashed: no warning
            Tracing.traceIdOf(UUID.randomUUID().toString());
            assertEquals(0, records.size());

            // the same non-UUID value warns once, however often it is mapped
            String value = "not-a-uuid-" + UUID.randomUUID();
            Tracing.traceIdOf(value);
            Tracing.traceIdOf(value);
            assertEquals(1, records.size());
            assertEquals(Level.WARNING, records.get(0).getLevel());

            // control characters cannot forge a log line, and a long value is cut at 64 characters
            records.clear();
            String hostile = "x" + UUID.randomUUID() + "\nSEVERE: forged\r\n";
            Tracing.traceIdOf(hostile);
            assertEquals(1, records.size());
            String shown = String.valueOf(records.get(0).getParameters()[0]);
            assertFalse(shown.contains("\n") || shown.contains("\r"));

            records.clear();
            Tracing.traceIdOf("L" + "y".repeat(200) + UUID.randomUUID());
            assertEquals(64 + 3, String.valueOf(records.get(0).getParameters()[0]).length());

            // null is hashed silently (nothing to show)
            records.clear();
            Tracing.traceIdOf(null);
            assertEquals(0, records.size());
        } finally {
            logger.removeHandler(capture);
        }
    }
}
