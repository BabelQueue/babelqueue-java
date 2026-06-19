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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Optional OpenTelemetry tracing for a babelqueue producer or consumer (ADR-0025) — the Java
 * mirror of the Go {@code babelqueue-go/otel}, Python {@code babelqueue.otel} and Node
 * {@code @babelqueue/core/otel} helpers.
 *
 * <p>Produce/consume spans are correlated across every hop and SDK through the envelope's
 * {@code trace_id} — a UUID, which maps 1:1 to a 32-hex OpenTelemetry trace id ({@link #traceIdOf}
 * / {@link #uuidOf}). A non-UUID {@code trace_id} is hashed deterministically (SHA-256).
 *
 * <ul>
 *   <li><b>Consumer:</b> {@link #wrapHandler} emits a {@code CONSUMER} span {@code process <urn>}
 *       in the {@code trace_id}-derived trace, recording the handler's error/status. It reuses
 *       the shared {@link Handler}, so it composes with {@code Idempotent.wrap} /
 *       {@code SchemaValidation.wrap}.</li>
 *   <li><b>Producer:</b> {@link #publish} emits a {@code PRODUCER} span {@code publish <urn>} that
 *       carries the active trace's id into the message's {@code trace_id}, then writes it via a
 *       {@link Sender}.</li>
 * </ul>
 *
 * <p>Every hop that shares a {@code trace_id} shares one OTel trace. Exact cross-hop <i>span</i>
 * parent-child linkage (W3C {@code traceparent} as a transport header) is a documented follow-up.
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
     * derived from the envelope's {@code trace_id}, recording the handler's error/status. The
     * handler receives the full {@link Envelope} as before.
     *
     * @param tracer  the OTel tracer
     * @param handler the handler to instrument
     * @return the wrapped handler
     */
    public static Handler wrapHandler(Tracer tracer, Handler handler) {
        return envelope -> {
            Span span = tracer.spanBuilder("process " + nullToEmpty(envelope.job()))
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(parentContext(envelope.traceId()))
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
        Span span = tracer.spanBuilder("publish " + urn)
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute("messaging.system", SYSTEM)
            .setAttribute("messaging.operation", "publish")
            .setAttribute("messaging.destination.name", urn)
            .startSpan();
        try (Scope scope = span.makeCurrent()) {
            String traceId = uuidOf(span.getSpanContext().getTraceId());
            Envelope envelope = EnvelopeCodec.make(urn, data, queue, traceId);
            send.send(envelope);
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
