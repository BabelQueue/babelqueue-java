package com.babelqueue.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Map;

/**
 * W3C {@code traceparent} inject/extract over an out-of-band transport-header map (ADR-0028,
 * implementing ADR-0025 Option 2) — the Java mirror of Go's {@code traceparent.go}, Python's
 * {@code _inject_traceparent}/{@code _remote_parent_from_headers} and Node's
 * {@code injectTraceparent}/{@code remoteParentFromHeaders}.
 *
 * <p>The producer writes the active span context onto the headers as a {@code traceparent} (and
 * {@code tracestate}); the consumer reads it back and starts its span as a true <b>child</b> of
 * the producer span — real cross-hop parent→child linkage, not merely the {@code trace_id}-derived
 * shared trace of v0.1. The header rides <b>beside</b> the frozen envelope on the transport's
 * per-message metadata channel — the same out-of-band seam as the replay-bypass marker
 * (ADR-0027, {@link com.babelqueue.Replay}) — so the wire envelope is never touched (GR-1) and
 * {@code schema_version} stays {@code 1}.
 *
 * <p>The W3C wire format is produced by OpenTelemetry's own
 * {@link W3CTraceContextPropagator}, which lives in {@code opentelemetry-api} — the module the
 * core already declares as an <b>optional</b> dependency (GR-7) — so no new dependency is pulled
 * and a babelqueue {@code traceparent} interoperates with any OTel SDK or W3C-compliant peer.
 */
final class Traceparent {

    /**
     * The out-of-band transport-header key carrying the W3C {@code traceparent} (ADR-0028). It is
     * the W3C standard name, so it interoperates with any OTel SDK or W3C-compliant peer.
     */
    static final String HEADER_TRACEPARENT = "traceparent";

    /** The out-of-band transport-header key carrying the optional W3C {@code tracestate}. */
    static final String HEADER_TRACESTATE = "tracestate";

    /**
     * The W3C Trace Context propagator. It reads/writes the {@code traceparent} (and
     * {@code tracestate}) headers — exactly the wire format ADR-0028 names.
     */
    private static final TextMapPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();

    /** Writes header entries into a plain {@code Map<String, String>} carrier. */
    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;

    /** Reads header entries out of a plain {@code Map<String, String>} carrier. */
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private Traceparent() {
    }

    /**
     * Writes the active span context in {@code context} as a W3C {@code traceparent} (and
     * {@code tracestate}, when present) into {@code headers}, returning the same map. The producer
     * half: the populated map is handed to a {@link HeaderSender} so the consumer can reconstruct
     * the remote parent. When {@code context} carries no valid span context the propagator writes
     * nothing and the map is returned unchanged — a no-trace publish stays header-free.
     *
     * @param context the context whose active span context to inject
     * @param headers the out-of-band header map to write into (must be mutable)
     * @return {@code headers}, with {@code traceparent}/{@code tracestate} added when valid
     */
    static Map<String, String> inject(Context context, Map<String, String> headers) {
        PROPAGATOR.inject(context, headers, SETTER);
        return headers;
    }

    /**
     * Extracts a W3C {@code traceparent} (and {@code tracestate}) from a delivered message's
     * out-of-band {@code headers} and returns a {@link Context} carrying the resulting remote
     * parent span context, or {@code null} when no valid {@code traceparent} is present.
     *
     * <p>The consumer half of true cross-hop parent-child linkage: a span started from the
     * returned context is a child of the producer's span (remote parent). {@code null} signals the
     * caller to fall back to the v0.1 {@code trace_id}-derived parent (ADR-0025 Option 1), so a
     * malformed, empty, or absent header never regresses a message from a pre-0028 (or
     * header-blind) producer.
     *
     * @param headers the delivered message's out-of-band headers (may be {@code null}/empty)
     * @return a context carrying the remote parent, or {@code null} when none is valid
     */
    static Context remoteParentFromHeaders(Map<String, String> headers) {
        if (headers == null || headers.get(HEADER_TRACEPARENT) == null) {
            return null;
        }
        Context extracted = PROPAGATOR.extract(Context.root(), headers, GETTER);
        if (!Span.fromContext(extracted).getSpanContext().isValid()) {
            return null;
        }
        return extracted;
    }
}
