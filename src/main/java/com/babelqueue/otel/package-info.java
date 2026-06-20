/**
 * Optional OpenTelemetry tracing for babelqueue (ADR-0025 v0.1 + ADR-0028 v0.2) — the Java mirror
 * of the Go {@code babelqueue-go/otel}, Python {@code babelqueue.otel} and Node
 * {@code @babelqueue/core/otel} modules.
 *
 * <p>{@link com.babelqueue.otel.Tracing} emits a {@code CONSUMER} span per handled message and a
 * {@code PRODUCER} span per publish, propagating the trace across every hop and SDK at two layered
 * levels:
 *
 * <ul>
 *   <li><b>{@code trace_id} ↔ OTel trace id (v0.1):</b> the envelope's {@code trace_id} — a UUID —
 *       maps 1:1 to a 32-hex OpenTelemetry trace id, so every hop that shares a {@code trace_id}
 *       shares one OTel trace (correlation + per-hop timing) with zero wire/transport change.</li>
 *   <li><b>W3C {@code traceparent} transport header (v0.2):</b> the producer also injects the
 *       active span context as a {@code traceparent} onto an out-of-band header map that rides
 *       beside the frozen envelope ({@link com.babelqueue.otel.HeaderSender}); the consumer reads it
 *       and starts its span as a true child of the producer span (a delivered-headers
 *       {@link java.util.function.Supplier} on {@code wrapHandler}). With no {@code traceparent}
 *       present it falls back to the v0.1 {@code trace_id} parent — a strict, backward-compatible
 *       upgrade.</li>
 * </ul>
 *
 * <p>The wire envelope is untouched (GR-1, {@code schema_version} stays {@code 1}), and
 * {@code io.opentelemetry:opentelemetry-api} (which itself ships OTel's W3C propagator) is declared
 * as an <b>optional</b> dependency, so the core stays zero-dependency for users who do not opt in
 * (GR-7).
 *
 * <p>This module delivers the v0.2 <i>mechanism</i>. The Java transports live in separate artifacts
 * ({@code babelqueue-java-sqs}, {@code babelqueue-java-redis}, {@code babelqueue-spring}), so
 * carrying the out-of-band header map on each broker's native per-message metadata channel is a
 * documented per-transport follow-up.
 */
package com.babelqueue.otel;
