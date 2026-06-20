package com.babelqueue.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v0.2 W3C {@code traceparent} transport-header propagation (ADR-0028): the producer injects the
 * active span's {@code traceparent} onto an out-of-band header map, the consumer reads it back and
 * starts its span as a true child of the producer span across a simulated hop. Proves the
 * cross-hop parent-child link, the no-header / malformed fallback to the v0.1 {@code trace_id}
 * parent, the belt-and-braces {@code trace_id} stamping, and that the header rides beside the
 * frozen envelope (never inside it).
 */
class TraceparentTest {

    private static final String TRACE_ID = "7b3f9c2a-e41d-4f88-9b2a-1c0d5e6f7a8b";

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

    private SpanData spanNamed(String name) {
        return exporter.getFinishedSpanItems().stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no span named " + name));
    }

    // ---- the inject/extract helper in isolation -----------------------------------------------

    @Test
    void injectWritesActiveSpanContextAndExtractRecoversIt() {
        // Start a real recording span and inject from a context built explicitly from it.
        Span span = tracer.spanBuilder("producer").startSpan();
        SpanContext producer = span.getSpanContext();
        Map<String, String> headers = new HashMap<>();
        Traceparent.inject(Context.root().with(span), headers);
        span.end();

        assertTrue(headers.containsKey("traceparent"));
        assertTrue(headers.get("traceparent").contains(producer.getTraceId()));
        assertTrue(headers.get("traceparent").contains(producer.getSpanId()));

        Context extracted = Traceparent.remoteParentFromHeaders(headers);
        SpanContext remote = Span.fromContext(extracted).getSpanContext();
        assertTrue(remote.isValid());
        assertTrue(remote.isRemote());
        assertEquals(producer.getTraceId(), remote.getTraceId());
        assertEquals(producer.getSpanId(), remote.getSpanId());
    }

    @Test
    void remoteParentIsNullForAbsentEmptyAndMalformedTraceparent() {
        assertNull(Traceparent.remoteParentFromHeaders(null));
        assertNull(Traceparent.remoteParentFromHeaders(Map.of()));
        assertNull(Traceparent.remoteParentFromHeaders(Map.of("traceparent", "")));
        assertNull(Traceparent.remoteParentFromHeaders(Map.of("traceparent", "not-a-traceparent")));
        // all-zero trace/span ids are invalid per the W3C format
        assertNull(Traceparent.remoteParentFromHeaders(
            Map.of("traceparent", "00-00000000000000000000000000000000-0000000000000000-01")));
    }

    @Test
    void tracestateRoundTrips() {
        // Build an active context with a known span context carrying tracestate, inject, extract.
        SpanContext seed = SpanContext.create(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            io.opentelemetry.api.trace.TraceFlags.getSampled(),
            io.opentelemetry.api.trace.TraceState.builder().put("vendor", "v1").build());
        Map<String, String> headers = new HashMap<>();
        Traceparent.inject(Context.root().with(Span.wrap(seed)), headers);

        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", headers.get("traceparent"));
        assertEquals("vendor=v1", headers.get("tracestate"));

        SpanContext remote = Span.fromContext(Traceparent.remoteParentFromHeaders(headers)).getSpanContext();
        assertEquals("v1", remote.getTraceState().get("vendor"));
    }

    // ---- producer → consumer hop through Tracing ----------------------------------------------

    @Test
    void consumerSpanIsTrueChildOfProducerSpanAcrossAHop() throws Exception {
        Envelope[] sent = {null};
        Map<String, String> carrier = new HashMap<>();

        // PRODUCER: HeaderSender overload injects the producer span's traceparent into the map.
        String id = Tracing.publish(tracer, "urn:babel:orders:created", Map.of("order_id", 1),
            (envelope, headers) -> {
                sent[0] = envelope;
                carrier.putAll(headers);
            });

        SpanData producer = spanNamed("publish urn:babel:orders:created");
        assertEquals(SpanKind.PRODUCER, producer.getKind());
        // traceparent rides out of band — never inside the frozen envelope.
        assertFalse(EnvelopeCodec.encode(sent[0]).contains("traceparent"));
        assertTrue(carrier.containsKey("traceparent"));
        // v0.1 belt-and-braces: the published trace_id still encodes the producer trace.
        assertEquals(producer.getSpanContext().getTraceId(), Tracing.traceIdOf(sent[0].traceId()));
        assertEquals(id, sent[0].meta().id());

        // CONSUMER: wrapHandler reads the delivered headers and parents on the remote producer span.
        boolean[] called = {false};
        Tracing.wrapHandler(tracer, e -> called[0] = true, () -> carrier).handle(sent[0]);
        assertTrue(called[0]);

        SpanData consumer = spanNamed("process urn:babel:orders:created");
        assertEquals(SpanKind.CONSUMER, consumer.getKind());
        // same trace, and the consumer's parent IS the producer span (true cross-hop link).
        assertEquals(producer.getSpanContext().getTraceId(), consumer.getSpanContext().getTraceId());
        assertEquals(producer.getSpanContext().getSpanId(), consumer.getParentSpanContext().getSpanId());
        assertTrue(consumer.getParentSpanContext().isRemote());
    }

    @Test
    void consumerFallsBackToTraceIdParentWhenNoHeaderCarried() throws Exception {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of(), "orders", TRACE_ID);

        // empty carrier — no traceparent — must behave exactly like the v0.1 path.
        Tracing.wrapHandler(tracer, e -> { }, HashMap::new).handle(env);

        SpanData consumer = spanNamed("process urn:babel:orders:created");
        assertEquals(Tracing.traceIdOf(TRACE_ID), consumer.getSpanContext().getTraceId());
        // the parent is the deterministic trace_id-derived remote parent, not a producer span.
        assertTrue(consumer.getParentSpanContext().isValid());
        assertTrue(consumer.getParentSpanContext().isRemote());
    }

    @Test
    void consumerWithMalformedHeaderFallsBackToTraceIdParent() throws Exception {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of(), "orders", TRACE_ID);
        Map<String, String> garbage = Map.of("traceparent", "garbage");

        Tracing.wrapHandler(tracer, e -> { }, () -> garbage).handle(env);

        SpanData consumer = spanNamed("process urn:babel:orders:created");
        // fell back to the trace_id-derived trace, NOT a trace from the garbage header.
        assertEquals(Tracing.traceIdOf(TRACE_ID), consumer.getSpanContext().getTraceId());
    }

    @Test
    void plainSenderOverloadCarriesNoTraceparentButStillStampsTraceId() throws Exception {
        Envelope[] sent = {null};

        // The v0.1 Sender overload (no header channel) must not propagate a traceparent...
        String id = Tracing.publish(tracer, "urn:babel:orders:created", Map.of(), e -> sent[0] = e);

        SpanData producer = spanNamed("publish urn:babel:orders:created");
        assertEquals(id, sent[0].meta().id());
        // ...but it still stamps the trace_id (v0.1 correlation), and the envelope is header-free.
        assertEquals(producer.getSpanContext().getTraceId(), Tracing.traceIdOf(sent[0].traceId()));
        assertFalse(EnvelopeCodec.encode(sent[0]).contains("traceparent"));
    }

    @Test
    void publishToDefaultQueueViaHeaderSender() throws Exception {
        Envelope[] sent = {null};

        Tracing.publish(tracer, "urn:babel:orders:created", Map.of("k", "v"),
            (envelope, headers) -> sent[0] = envelope);

        assertEquals("default", sent[0].meta().queue());
        assertEquals(SpanKind.PRODUCER, spanNamed("publish urn:babel:orders:created").getKind());
    }

    @Test
    void wrapHandlerWithNullHeadersSupplierUsesV01Parent() throws Exception {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of(), "orders", TRACE_ID);

        // The 2-arg overload delegates to a null supplier — pure v0.1 behaviour, no regression.
        Tracing.wrapHandler(tracer, e -> { }).handle(env);

        SpanData consumer = spanNamed("process urn:babel:orders:created");
        assertEquals(Tracing.traceIdOf(TRACE_ID), consumer.getSpanContext().getTraceId());
        assertNotEquals("0000000000000000", consumer.getParentSpanContext().getSpanId());
    }
}
