package com.babelqueue.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
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
        assertEquals("process urn:babel:orders:created", span.getName());
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
        assertEquals("publish urn:babel:orders:created", span.getName());
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
}
