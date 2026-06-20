package com.babelqueue.otel;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import com.babelqueue.Meta;
import com.babelqueue.idempotency.Handler;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Optional OpenTelemetry tracing for a babelqueue producer or consumer — the Java mirror of the Go
 * {@code babelqueue-go/otel}, Python {@code babelqueue.otel} and Node {@code @babelqueue/core/otel}
 * helpers.
 *
 * <p>Cross-hop trace propagation works at two layered levels:
 *
 * <ul>
 *   <li><b>{@code trace_id} ↔ OTel trace id (ADR-0025, v0.1):</b> the envelope's {@code trace_id} —
 *       a UUID — maps 1:1 to a 32-hex OpenTelemetry trace id ({@link #traceIdOf} / {@link #uuidOf};
 *       a non-UUID {@code trace_id} is hashed deterministically, SHA-256). Every hop that shares a
 *       {@code trace_id} shares one OTel trace — correlation and per-hop timing with zero
 *       wire/transport change.</li>
 *   <li><b>W3C {@code traceparent} transport header (ADR-0028, v0.2):</b> the producer also injects
 *       the active span context as a {@code traceparent} (and {@code tracestate}) onto an
 *       out-of-band header map that rides <b>beside</b> the frozen envelope, never inside it (GR-1).
 *       The consumer reads it and starts its span as a true <b>child</b> of the producer span —
 *       real cross-hop parent→child linkage and per-hop span timing, not just a shared trace. With
 *       no {@code traceparent} present it falls back to the v0.1 {@code trace_id} behaviour, so
 *       enabling propagation is a strict, backward-compatible upgrade — never a regression.</li>
 * </ul>
 *
 * <ul>
 *   <li><b>Consumer:</b> {@link #wrapHandler(Tracer, Handler)} emits a {@code CONSUMER} span
 *       {@code process <urn>} parented on the {@code trace_id}-derived trace (v0.1). The
 *       {@link #wrapHandler(Tracer, Handler, Supplier) headers overload} additionally reads the
 *       delivered message's out-of-band headers and, when they carry a valid {@code traceparent},
 *       parents the span as a true child of the producer span (v0.2). It reuses the shared
 *       {@link Handler}, so it composes with {@code Idempotent.wrap} / {@code SchemaValidation.wrap}.</li>
 *   <li><b>Producer:</b> {@link #publish(Tracer, String, Map, String, Sender)} emits a
 *       {@code PRODUCER} span {@code publish <urn>} that carries the active trace's id into the
 *       message's {@code trace_id} (v0.1), then writes it via a {@link Sender}. The
 *       {@link #publish(Tracer, String, Map, String, HeaderSender) HeaderSender overload}
 *       additionally injects the active span's {@code traceparent} onto an out-of-band header map
 *       handed to the transport (v0.2).</li>
 * </ul>
 *
 * <p>The wire envelope is untouched (GR-1) and the core never imports OpenTelemetry on a
 * non-opt-in path: {@code io.opentelemetry:opentelemetry-api} (which itself ships OTel's W3C
 * propagator) is an <b>optional</b> dependency, so the core stays zero-dependency for users who do
 * not opt in (GR-7).
 *
 * <p><b>Transport wiring is a documented follow-up.</b> The Java transports live in separate
 * artifacts ({@code babelqueue-java-sqs}, {@code babelqueue-java-redis}, {@code babelqueue-spring}).
 * This core delivers the v0.2 <i>mechanism</i> — the out-of-band header seam ({@link HeaderSender}
 * produce-side, a delivered-headers {@link Supplier} consume-side) plus the {@code traceparent}
 * inject/extract. Carrying the header map on each transport's native per-message metadata channel
 * (AMQP headers, SQS {@code MessageAttributes}, a Redis transport-owned frame), beside the contract
 * {@code bq-*}/{@code x-*} headers, is the per-transport rollout — the same seam ADR-0027 and the
 * broker bindings roll out per SDK. Until a transport wires it, propagation degrades to the v0.1
 * {@code trace_id} correlation with no error.
 */
public final class Tracing {

    private static final String SYSTEM = "babelqueue";
    private static final String DEFAULT_QUEUE = "default";
    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";
    private static final String INVALID_SPAN_ID = "0000000000000000";
    private static final Pattern HEX_32 = Pattern.compile("[0-9a-f]{32}");

    private Tracing() {
    }

    /**
     * Maps an envelope {@code trace_id} to a deterministic 32-hex OTel trace id: a UUID maps to
     * its hex bytes; any other string is hashed (SHA-256, first 16 bytes). Never the all-zero
     * (invalid) trace id. The inverse of {@link #uuidOf} for the UUID case.
     *
     * @param traceId the envelope {@code trace_id}
     * @return a valid 32-hex OTel trace id
     */
    public static String traceIdOf(String traceId) {
        String hex = normalizeHex(traceId);
        if (HEX_32.matcher(hex).matches() && !hex.equals(INVALID_TRACE_ID)) {
            return hex;
        }
        return toHex(sha256(traceId == null ? "" : traceId), 16);
    }

    /**
     * Formats a 32-hex OTel trace id as a canonical UUID string — the form a producer stamps into
     * the message's {@code trace_id} so a consumer can recover the same trace id via
     * {@link #traceIdOf}.
     *
     * @param traceIdHex a 32-hex OTel trace id
     * @return the canonical UUID string
     */
    public static String uuidOf(String traceIdHex) {
        StringBuilder h = new StringBuilder(normalizeHex(traceIdHex));
        while (h.length() < 32) {
            h.insert(0, '0');
        }
        String s = h.substring(0, 32);
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
            + "-" + s.substring(16, 20) + "-" + s.substring(20, 32);
    }

    /**
     * Wraps a consume handler to emit a {@code CONSUMER} span per message, in the OTel trace
     * derived from the envelope's {@code trace_id} (ADR-0025 Option 1), recording the handler's
     * error/status. The handler receives the full {@link Envelope} as before.
     *
     * <p>This is the v0.1 shape: it parents the span on the {@code trace_id}-derived trace only. To
     * additionally start the span as a true child of the producer span when a W3C {@code traceparent}
     * was carried beside the envelope, use {@link #wrapHandler(Tracer, Handler, Supplier)}.
     *
     * @param tracer  the OTel tracer
     * @param handler the handler to instrument
     * @return the wrapped handler
     */
    public static Handler wrapHandler(Tracer tracer, Handler handler) {
        return wrapHandler(tracer, handler, null);
    }

    /**
     * Wraps a consume handler to emit a {@code CONSUMER} span per message, recording the handler's
     * error/status. The handler receives the full {@link Envelope} as before.
     *
     * <p><b>Parent selection (ADR-0028):</b> when {@code headers} supplies the delivered message's
     * out-of-band headers and they carry a valid W3C {@code traceparent}, the span is started as a
     * true <b>child</b> of the producer span — real cross-hop parent→child linkage with per-hop
     * span timing. When no {@code traceparent} is present (a {@code null} supplier, a {@code null}
     * or empty map, or a malformed value) it falls back to the v0.1 behaviour: a remote parent
     * derived from the envelope's {@code trace_id} (ADR-0025 Option 1), which shares the trace but
     * not the exact span edge. So enabling {@code traceparent} propagation is a strict,
     * backward-compatible upgrade — no regression for messages produced without it.
     *
     * <p>{@code headers} is a {@link Supplier} a transport adapter wires to a reserved message's
     * out-of-band headers (e.g. {@link com.babelqueue.Redrive.Reserved#headers()}); it is read once
     * per delivery, after the envelope is in hand. The Java transports live in separate artifacts,
     * so wiring this supplier to each broker's per-message metadata channel is a documented
     * follow-up.
     *
     * @param tracer  the OTel tracer
     * @param handler the handler to instrument
     * @param headers a supplier of the delivered message's out-of-band headers, or {@code null} to
     *                always use the v0.1 {@code trace_id}-derived parent
     * @return the wrapped handler
     */
    public static Handler wrapHandler(Tracer tracer, Handler handler, Supplier<Map<String, String>> headers) {
        return envelope -> {
            Span span = tracer.spanBuilder("process " + nullToEmpty(envelope.job()))
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(consumerParent(envelope.traceId(), headers))
                .setAllAttributes(consumeAttributes(envelope))
                .startSpan();
            try (Scope scope = span.makeCurrent()) {
                handler.handle(envelope);
            } catch (Exception e) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR, nullToEmpty(e.getMessage()));
                throw e;
            } finally {
                span.end();
            }
        };
    }

    /**
     * Publishes {@code (urn, data)} to the {@code "default"} queue under a {@code PRODUCER} span.
     * See {@link #publish(Tracer, String, Map, String, Sender)}.
     *
     * @param tracer the OTel tracer
     * @param urn    the message URN
     * @param data   the message data
     * @param send   the transport write
     * @return the published message id ({@code meta.id})
     * @throws Exception if {@code send} fails (recorded on the span and re-thrown)
     */
    public static String publish(Tracer tracer, String urn, Map<String, Object> data, Sender send)
        throws Exception {
        return publish(tracer, urn, data, DEFAULT_QUEUE, send);
    }

    /**
     * Emits a {@code PRODUCER} span {@code publish <urn>}, carrying the active trace's id into the
     * built envelope's {@code trace_id} so the downstream consumer recovers the same trace, then
     * writes the envelope via {@code send}.
     *
     * @param tracer the OTel tracer
     * @param urn    the message URN
     * @param data   the message data
     * @param queue  the destination queue
     * @param send   the transport write
     * @return the published message id ({@code meta.id})
     * @throws Exception if {@code send} fails (recorded on the span and re-thrown)
     */
    public static String publish(
        Tracer tracer, String urn, Map<String, Object> data, String queue, Sender send)
        throws Exception {
        return publish(tracer, urn, data, queue, (envelope, headers) -> send.send(envelope));
    }

    /**
     * Publishes {@code (urn, data)} to the {@code "default"} queue under a {@code PRODUCER} span,
     * propagating a W3C {@code traceparent} on the out-of-band header map (ADR-0028). See
     * {@link #publish(Tracer, String, Map, String, HeaderSender)}.
     *
     * @param tracer the OTel tracer
     * @param urn    the message URN
     * @param data   the message data
     * @param send   the transport write, receiving the envelope and its out-of-band headers
     * @return the published message id ({@code meta.id})
     * @throws Exception if {@code send} fails (recorded on the span and re-thrown)
     */
    public static String publish(Tracer tracer, String urn, Map<String, Object> data, HeaderSender send)
        throws Exception {
        return publish(tracer, urn, data, DEFAULT_QUEUE, send);
    }

    /**
     * Emits a {@code PRODUCER} span {@code publish <urn>} and propagates the trace downstream two
     * ways (ADR-0028):
     *
     * <ul>
     *   <li>It injects the active span context as a W3C {@code traceparent} (and {@code tracestate})
     *       onto a fresh out-of-band header map, so a consumer can start its span as a true
     *       <b>child</b> of this producer span — real cross-hop parent→child linkage (v0.2). The
     *       header rides beside the frozen envelope, never in it (GR-1); a transport that cannot
     *       carry headers may simply ignore the map (the {@code traceparent} is then not propagated
     *       — no error).</li>
     *   <li>It also carries the active trace's id into the built envelope's {@code trace_id} (the
     *       v0.1 behaviour), so even a consumer that ignores the header — or a transport that drops
     *       it — still recovers the same trace (correlation without exact span linkage).</li>
     * </ul>
     *
     * <p>The header map is handed to {@code send} alongside the built envelope.
     *
     * @param tracer the OTel tracer
     * @param urn    the message URN
     * @param data   the message data
     * @param queue  the destination queue
     * @param send   the transport write, receiving the envelope and its out-of-band headers
     * @return the published message id ({@code meta.id})
     * @throws Exception if {@code send} fails (recorded on the span and re-thrown)
     */
    public static String publish(
        Tracer tracer, String urn, Map<String, Object> data, String queue, HeaderSender send)
        throws Exception {
        Span span = tracer.spanBuilder("publish " + urn)
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute("messaging.system", SYSTEM)
            .setAttribute("messaging.operation", "publish")
            .setAttribute("messaging.destination.name", urn)
            .startSpan();
        try (Scope scope = span.makeCurrent()) {
            String traceId = uuidOf(span.getSpanContext().getTraceId());
            // Inject from a context built explicitly from *this* producer span, so the traceparent
            // is correct without relying on an ambient current context being wired.
            Map<String, String> headers = Traceparent.inject(
                Context.current().with(span), new LinkedHashMap<>());
            Envelope envelope = EnvelopeCodec.make(urn, data, queue, traceId);
            send.send(envelope, headers);
            String id = envelope.meta().id();
            span.setAttribute("messaging.message.id", id);
            return id;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, nullToEmpty(e.getMessage()));
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Selects the consumer span's parent: a true remote parent extracted from a carried W3C
     * {@code traceparent} (v0.2) when {@code headers} supplies a valid one, else the v0.1
     * {@code trace_id}-derived parent (ADR-0025 Option 1).
     */
    private static Context consumerParent(String traceId, Supplier<Map<String, String>> headers) {
        if (headers != null) {
            Context remote = Traceparent.remoteParentFromHeaders(headers.get());
            if (remote != null) {
                return remote;
            }
        }
        return parentContext(traceId);
    }

    /** A context carrying a remote parent in the {@code trace_id}-derived trace. */
    private static Context parentContext(String traceId) {
        SpanContext sc = SpanContext.createFromRemoteParent(
            traceIdOf(traceId), spanIdOf(traceId), TraceFlags.getSampled(), TraceState.getDefault());
        return Context.root().with(Span.wrap(sc));
    }

    private static Attributes consumeAttributes(Envelope envelope) {
        Meta meta = envelope.meta();
        return Attributes.builder()
            .put("messaging.system", SYSTEM)
            .put("messaging.operation", "process")
            .put("messaging.destination.name", meta == null ? "" : nullToEmpty(meta.queue()))
            .put("messaging.message.id", meta == null ? "" : nullToEmpty(meta.id()))
            .put("messaging.message.conversation_id", nullToEmpty(envelope.traceId()))
            .put("messaging.babelqueue.attempts", (long) envelope.attempts())
            .build();
    }

    /** Deterministic, non-zero 16-hex span id so the remote parent context is valid. */
    private static String spanIdOf(String traceId) {
        String sid = toHex(sha256("babelqueue-span:" + (traceId == null ? "" : traceId)), 8);
        return sid.equals(INVALID_SPAN_ID) ? "0000000000000001" : sid;
    }

    private static String normalizeHex(String s) {
        return s == null ? "" : s.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private static String toHex(byte[] bytes, int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(bytes[i] & 0xF, 16));
        }
        return sb.toString();
    }
}
