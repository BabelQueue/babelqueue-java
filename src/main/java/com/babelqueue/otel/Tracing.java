package com.babelqueue.otel;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import com.babelqueue.Meta;
import com.babelqueue.idempotency.Handler;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanBuilder;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
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
 * <p><b>Span shape (ADR-0038, semantic conventions {@value #SEMCONV_VERSION}):</b>
 *
 * <ul>
 *   <li><b>Consumer:</b> {@link #wrapHandler(Tracer, Handler)} emits a {@code CONSUMER} span
 *       {@code process <queue>} (the bare {@code process} when {@code meta.queue} is absent or
 *       blank) parented on the {@code trace_id}-derived trace (v0.1). The
 *       {@link #wrapHandler(Tracer, Handler, Supplier) headers overload} additionally reads the
 *       delivered message's out-of-band headers and, when they carry a valid {@code traceparent},
 *       parents the span as a true child of the producer span (v0.2). It reuses the shared
 *       {@link Handler}, so it composes with {@code Idempotent.wrap} / {@code SchemaValidation.wrap}.</li>
 *   <li><b>Producer:</b> {@link #publish(Tracer, String, Map, String, Sender)} emits a
 *       {@code PRODUCER} span {@code send <queue>} that carries the active trace's id into the
 *       message's {@code trace_id} (v0.1), then writes it via a {@link Sender}. The
 *       {@link #publish(Tracer, String, Map, String, HeaderSender) HeaderSender overload}
 *       additionally injects the active span's {@code traceparent} onto an out-of-band header map
 *       handed to the transport (v0.2).</li>
 *   <li><b>Broker name and consumer group:</b> {@code messaging.system} defaults to
 *       {@value #DEFAULT_SYSTEM} (<b>deprecated</b> default). Pass {@link Options#system(String)}
 *       (for example {@code "kafka"}) through the overloads that take an {@link Options} to write
 *       the broker name, and {@link Options#consumerGroup(String)} for
 *       {@code messaging.consumer.group.name}.</li>
 *   <li><b>Metrics (ADR-0039):</b> {@link Options#meter(Meter)} turns on the standard metric set
 *       ({@link Metrics}); without it the wrappers record no metrics.</li>
 *   <li><b>Transition attributes:</b> for this MINOR the deprecated {@code messaging.operation}
 *       and {@code messaging.babelqueue.attempts} are written next to their replacements; the
 *       next MINOR removes them.</li>
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

    /** The semantic-conventions release the span and metric shape follows (ADR-0038 section 1). */
    public static final String SEMCONV_VERSION = "1.44.0";

    /**
     * The schema URL of {@link #SEMCONV_VERSION}. The caller creates the {@link Tracer} (and the
     * {@link Meter}) and should attach it, for example
     * {@code provider.tracerBuilder("my-app").setSchemaUrl(Tracing.SCHEMA_URL).build()}; the SDK
     * never wraps or replaces a caller-supplied tracer.
     */
    public static final String SCHEMA_URL = "https://opentelemetry.io/schemas/" + SEMCONV_VERSION;

    /**
     * The {@code messaging.system} value written when no {@link Options#system(String) system} is
     * given. Deprecated (ADR-0038 section 5): pass the broker name instead.
     */
    public static final String DEFAULT_SYSTEM = "babelqueue";

    private static final Logger LOG = Logger.getLogger(Tracing.class.getName());
    /** Upper bound of the distinct hashed trace ids remembered for warning de-duplication. */
    private static final int MAX_WARNED_TRACE_IDS = 1024;
    private static final Set<String> WARNED_TRACE_IDS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final AttributeKey<String> A_SYSTEM = AttributeKey.stringKey("messaging.system");
    private static final AttributeKey<String> A_OPERATION_TYPE = AttributeKey.stringKey("messaging.operation.type");
    private static final AttributeKey<String> A_OPERATION_NAME = AttributeKey.stringKey("messaging.operation.name");
    private static final AttributeKey<String> A_OPERATION = AttributeKey.stringKey("messaging.operation");
    private static final AttributeKey<String> A_DESTINATION = AttributeKey.stringKey("messaging.destination.name");
    private static final AttributeKey<String> A_MESSAGE_ID = AttributeKey.stringKey("messaging.message.id");
    private static final AttributeKey<String> A_CONVERSATION_ID =
        AttributeKey.stringKey("messaging.message.conversation_id");
    private static final AttributeKey<String> A_CONSUMER_GROUP =
        AttributeKey.stringKey("messaging.consumer.group.name");
    private static final AttributeKey<String> A_URN = AttributeKey.stringKey("babelqueue.urn");
    private static final AttributeKey<Long> A_SCHEMA_VERSION = AttributeKey.longKey("babelqueue.schema_version");
    private static final AttributeKey<Long> A_ATTEMPTS = AttributeKey.longKey("babelqueue.attempts");
    private static final AttributeKey<Long> A_ATTEMPTS_DEPRECATED =
        AttributeKey.longKey("messaging.babelqueue.attempts");
    private static final AttributeKey<String> A_ERROR_TYPE = AttributeKey.stringKey("error.type");

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
     * <p>A value that is not a UUID is preserved and forwarded unchanged everywhere else (ADR-0038
     * section 7); only this mapping hashes it, and it logs one {@code WARNING} per distinct value
     * (bounded) on the {@code com.babelqueue.otel.Tracing} logger.
     *
     * @param traceId the envelope {@code trace_id}
     * @return a valid 32-hex OTel trace id
     */
    public static String traceIdOf(String traceId) {
        String hex = normalizeHex(traceId);
        if (HEX_32.matcher(hex).matches() && !hex.equals(INVALID_TRACE_ID)) {
            return hex;
        }
        warnHashed(traceId);
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
        return wrapHandler(tracer, handler, headers, null);
    }

    /**
     * Same as {@link #wrapHandler(Tracer, Handler, Supplier)}, with {@link Options}: the broker
     * name ({@code messaging.system}), the Kafka {@code consumer_group} and a {@link Meter} for
     * the standard metrics (ADR-0038, ADR-0039).
     *
     * <p>Per delivery the wrapper records {@code messaging.client.consumed.messages} +1,
     * {@code babelqueue.retries} +1 when {@code attempts > 0}, and the handler's wall time in
     * {@code messaging.process.duration} (with {@code error.type} on failure) — only when
     * {@link Options#meter(Meter)} was given. The handler's result (return or the rethrown error)
     * is unchanged.
     *
     * @param tracer  the OTel tracer
     * @param handler the handler to instrument
     * @param headers a supplier of the delivered message's out-of-band headers, or {@code null} to
     *                always use the v0.1 {@code trace_id}-derived parent
     * @param options the instrumentation options, or {@code null} for the defaults
     * @return the wrapped handler
     */
    public static Handler wrapHandler(
        Tracer tracer, Handler handler, Supplier<Map<String, String>> headers, Options options) {
        Options opts = options == null ? Options.DEFAULTS : options;
        return envelope -> {
            Meta meta = envelope.meta();
            String destination = meta == null || EnvelopeCodec.isBlankAscii(meta.queue()) ? null : meta.queue();
            Span span = tracer.spanBuilder(spanName("process", destination))
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(consumerParent(envelope.traceId(), headers))
                .setAllAttributes(consumeAttributes(envelope, destination, opts))
                .startSpan();
            String errorType = null;
            long start = System.nanoTime();
            try (Scope scope = span.makeCurrent()) {
                handler.handle(envelope);
            } catch (Exception e) {
                errorType = recordFailure(span, e);
                throw e;
            } finally {
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                span.end();
                if (opts.metrics != null) {
                    opts.metrics.recordConsumed(opts.resolvedSystem(), destination, envelope.attempts(), seconds, errorType);
                }
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
     * Emits a {@code PRODUCER} span {@code send <queue>}, carrying the active trace's id into the
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
     * Emits a {@code PRODUCER} span {@code send <queue>} and propagates the trace downstream two
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
        return publish(tracer, urn, data, queue, send, null);
    }

    /**
     * Same as {@link #publish(Tracer, String, Map, String, Sender)}, with {@link Options} (the
     * broker name and a {@link Meter} for the standard metrics).
     *
     * @param tracer  the OTel tracer
     * @param urn     the message URN
     * @param data    the message data
     * @param queue   the destination queue, or {@code null} for {@code "default"}
     * @param send    the transport write
     * @param options the instrumentation options, or {@code null} for the defaults
     * @return the published message id ({@code meta.id})
     * @throws Exception if {@code send} fails (recorded on the span and re-thrown)
     */
    public static String publish(
        Tracer tracer, String urn, Map<String, Object> data, String queue, Sender send, Options options)
        throws Exception {
        return publish(tracer, urn, data, queue, (envelope, headers) -> send.send(envelope), options);
    }

    /**
     * Emits a {@code PRODUCER} span {@code send <queue>} and propagates the trace downstream two
     * ways (ADR-0028), like {@link #publish(Tracer, String, Map, String, HeaderSender)}, with
     * {@link Options}.
     *
     * <p>The destination is the queue the envelope is built for ({@code meta.queue}; {@code null}
     * means {@code "default"}, exactly as {@link EnvelopeCodec#make}); it is resolved before the
     * span starts. With a blank queue the span is the bare {@code send} and carries no
     * {@code messaging.destination.name}. The URN is {@code babelqueue.urn}, never in the name.
     *
     * <p>When {@link Options#meter(Meter)} was given, {@code messaging.client.sent.messages} is
     * incremented once per call — success or failure — with {@code error.type} on failure.
     *
     * @param tracer  the OTel tracer
     * @param urn     the message URN
     * @param data    the message data
     * @param queue   the destination queue, or {@code null} for {@code "default"}
     * @param send    the transport write, receiving the envelope and its out-of-band headers
     * @param options the instrumentation options, or {@code null} for the defaults
     * @return the published message id ({@code meta.id})
     * @throws Exception if {@code send} fails (recorded on the span and re-thrown)
     */
    public static String publish(
        Tracer tracer, String urn, Map<String, Object> data, String queue, HeaderSender send, Options options)
        throws Exception {
        Options opts = options == null ? Options.DEFAULTS : options;
        String resolvedQueue = queue == null ? DEFAULT_QUEUE : queue;
        String destination = EnvelopeCodec.isBlankAscii(resolvedQueue) ? null : resolvedQueue;
        SpanBuilder builder = tracer.spanBuilder(spanName("send", destination))
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute(A_SYSTEM, opts.resolvedSystem())
            .setAttribute(A_OPERATION_TYPE, "send")
            .setAttribute(A_OPERATION_NAME, "send")
            .setAttribute(A_URN, nullToEmpty(urn))
            .setAttribute(A_SCHEMA_VERSION, (long) EnvelopeCodec.SCHEMA_VERSION)
            .setAttribute(A_OPERATION, "publish"); // deprecated, dual-emitted for one MINOR
        if (destination != null) {
            builder.setAttribute(A_DESTINATION, destination);
        }
        Span span = builder.startSpan();
        String errorType = null;
        try (Scope scope = span.makeCurrent()) {
            String traceId = uuidOf(span.getSpanContext().getTraceId());
            // Inject from a context built explicitly from *this* producer span, so the traceparent
            // is correct without relying on an ambient current context being wired.
            Map<String, String> headers = Traceparent.inject(
                Context.current().with(span), new LinkedHashMap<>());
            Envelope envelope = EnvelopeCodec.make(urn, data, resolvedQueue, traceId);
            String id = envelope.meta().id();
            // The envelope exists from here on: the ids are on the span even if the send fails, and
            // babelqueue.urn is the envelope's job (ASCII-trimmed), the value the consumer span reports.
            span.setAttribute(A_URN, envelope.job());
            span.setAttribute(A_MESSAGE_ID, id);
            span.setAttribute(A_CONVERSATION_ID, envelope.traceId());
            send.send(envelope, headers);
            return id;
        } catch (Exception e) {
            errorType = recordFailure(span, e);
            throw e;
        } finally {
            span.end();
            if (opts.metrics != null) {
                opts.metrics.recordSent(opts.resolvedSystem(), destination, errorType);
            }
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

    private static Attributes consumeAttributes(Envelope envelope, String destination, Options options) {
        Meta meta = envelope.meta();
        long attempts = envelope.attempts();
        AttributesBuilder b = Attributes.builder()
            .put(A_SYSTEM, options.resolvedSystem())
            .put(A_OPERATION_TYPE, "process")
            .put(A_OPERATION_NAME, "process")
            .put(A_CONVERSATION_ID, nullToEmpty(envelope.traceId()))
            .put(A_URN, nullToEmpty(envelope.job()))
            .put(A_SCHEMA_VERSION, meta == null ? 0L : (long) meta.schemaVersion())
            .put(A_ATTEMPTS, attempts)
            .put(A_OPERATION, "process") // deprecated, dual-emitted for one MINOR
            .put(A_ATTEMPTS_DEPRECATED, attempts); // deprecated, dual-emitted for one MINOR
        if (destination != null) {
            b.put(A_DESTINATION, destination);
        }
        if (meta != null && meta.id() != null && !meta.id().isEmpty()) {
            b.put(A_MESSAGE_ID, meta.id());
        }
        if (options.consumerGroup != null) {
            b.put(A_CONSUMER_GROUP, options.consumerGroup);
        }
        return b.build();
    }

    /** {@code <operation> <destination>}, or the bare operation when there is no destination. */
    private static String spanName(String operation, String destination) {
        return destination == null ? operation : operation + " " + destination;
    }

    /**
     * Records {@code e} on {@code span} (event, status {@code ERROR} with the message, and
     * {@code error.type}); returns the {@code error.type} value for the metrics.
     */
    private static String recordFailure(Span span, Exception e) {
        String type = e.getClass().getName();
        if (type == null || type.isEmpty()) {
            type = "_OTHER";
        }
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, nullToEmpty(e.getMessage()));
        span.setAttribute(A_ERROR_TYPE, type);
        return type;
    }

    /** The {@code messaging.system} value for a given option ({@code null}/empty = default). */
    static String resolveSystem(String system) {
        return system == null || system.isEmpty() ? DEFAULT_SYSTEM : system;
    }

    /**
     * Logs one {@code WARNING} per distinct non-UUID {@code trace_id} that the OTel mapping hashes
     * (ADR-0038 section 7). Bounded, so a stream of unique ids cannot grow memory without limit.
     */
    private static void warnHashed(String traceId) {
        if (traceId == null || WARNED_TRACE_IDS.size() >= MAX_WARNED_TRACE_IDS || !WARNED_TRACE_IDS.add(traceId)) {
            return;
        }
        String shown = traceId.length() > 64 ? traceId.substring(0, 64) + "..." : traceId;
        StringBuilder safe = new StringBuilder(shown.length());
        shown.codePoints().forEach(cp -> safe.appendCodePoint(Character.isISOControl(cp) ? '?' : cp));
        LOG.log(Level.WARNING,
            "trace_id \"{0}\" is not a UUID; the OpenTelemetry trace id is derived by hashing it "
                + "(the trace_id itself is forwarded unchanged)", safe.toString());
    }

    /**
     * Instrumentation options of the producer and consumer wrappers (ADR-0038 section 2, ADR-0039
     * section 3). Immutable: each setter returns a new instance, so one {@code Options} can be
     * shared and reused across calls (its {@link Metrics} are created once).
     *
     * <pre>{@code
     * Tracing.Options opts = Tracing.Options.create().system("kafka").consumerGroup("billing").meter(meter);
     * }</pre>
     */
    public static final class Options {

        static final Options DEFAULTS = new Options(null, null, null);

        private final String system;
        private final String consumerGroup;
        private final Metrics metrics;

        private Options(String system, String consumerGroup, Metrics metrics) {
            this.system = system;
            this.consumerGroup = consumerGroup;
            this.metrics = metrics;
        }

        /** Options with no {@code system}, no consumer group and no metrics. */
        public static Options create() {
            return DEFAULTS;
        }

        /**
         * The {@code messaging.system} value: the broker name. Guidance (not validated, written as
         * given): {@code redis}, {@code rabbitmq}, {@code aws_sqs}, {@code servicebus},
         * {@code pulsar}, {@code kafka}, {@code activemq}. {@code null} or empty means absent:
         * {@value Tracing#DEFAULT_SYSTEM}, a deprecated default.
         *
         * @param system the broker name
         * @return the options with {@code system} set
         */
        public Options system(String system) {
            return new Options(system, consumerGroup, metrics);
        }

        /**
         * The {@code messaging.consumer.group.name} value (intended for Kafka; written whenever
         * given, consumer spans only). {@code null} or empty means absent.
         *
         * @param consumerGroup the consumer group
         * @return the options with {@code consumerGroup} set
         */
        public Options consumerGroup(String consumerGroup) {
            return new Options(system, consumerGroup == null || consumerGroup.isEmpty() ? null : consumerGroup, metrics);
        }

        /**
         * Turns on the standard metric set (ADR-0039) on {@code meter}; the instruments are
         * created once, here. {@code null} turns metrics off.
         *
         * @param meter the OpenTelemetry meter, or {@code null} for none
         * @return the options with metrics set
         */
        public Options meter(Meter meter) {
            return new Options(system, consumerGroup, meter == null ? null : Metrics.create(meter));
        }

        /**
         * The metric set built by {@link #meter(Meter)}, or {@code null} when no meter was given.
         * Use it to build the {@link Metrics#deadLetterObserver(String) dead-letter observer}.
         *
         * @return the metrics, or {@code null}
         */
        public Metrics metrics() {
            return metrics;
        }

        /**
         * The effective {@code messaging.system} value.
         *
         * @return {@link #system(String)} or {@value Tracing#DEFAULT_SYSTEM}
         */
        public String resolvedSystem() {
            return resolveSystem(system);
        }
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
