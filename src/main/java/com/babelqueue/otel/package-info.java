/**
 * Optional OpenTelemetry tracing for babelqueue (ADR-0025) — the Java mirror of the Go
 * {@code babelqueue-go/otel} module.
 *
 * <p>{@link com.babelqueue.otel.Tracing} emits a {@code CONSUMER} span per handled message and
 * a {@code PRODUCER} span per publish, correlating them across every hop and SDK through the
 * envelope's {@code trace_id} — a UUID, which maps 1:1 to a 32-hex OpenTelemetry trace id. The
 * wire envelope is untouched, and {@code io.opentelemetry:opentelemetry-api} is declared as an
 * <b>optional</b> dependency, so the core stays zero-dependency for users who do not opt in.
 *
 * <p>Every hop that shares a {@code trace_id} shares one OTel trace. Exact cross-hop <i>span</i>
 * parent-child linkage (W3C {@code traceparent} as a transport header) is a documented follow-up.
 */
package com.babelqueue.otel;
