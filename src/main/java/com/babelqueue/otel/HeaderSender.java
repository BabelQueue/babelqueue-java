package com.babelqueue.otel;

import com.babelqueue.Envelope;
import java.util.Map;

/**
 * The produce-side out-of-band header seam for {@link Tracing#publish(io.opentelemetry.api.trace.Tracer,
 * String, java.util.Map, String, HeaderSender)} — a {@link Sender} that also receives a
 * {@code Map<String, String>} of out-of-band transport headers to carry <b>beside</b> the frozen
 * envelope (GR-1), never inside it.
 *
 * <p>It is the Java counterpart of Go's {@code App.PublishWithHeaders} / Node's
 * {@code publish(..., send, {headers})}: the OTel producer injects the active span's W3C
 * {@code traceparent} into the map and hands it here, so a downstream consumer can start its span
 * as a true child of the producer span (ADR-0028). The {@code Map<String, String>} is the same
 * out-of-band header shape the core already agrees on for {@link com.babelqueue.Redrive.HeaderPublisher}
 * (the replay-bypass marker, ADR-0027) and for {@link com.babelqueue.Redrive.Reserved#headers()}.
 *
 * <p>An adapter carries the map on its transport's native per-message metadata channel (AMQP
 * message headers, SQS {@code MessageAttributes}, a Redis transport-owned frame), merging it beside
 * the contract {@code bq-*}/{@code x-*} headers. A transport with no such channel can ignore the
 * map (or be reached through the plain {@link Sender} overload) — propagation then degrades to the
 * v0.1 {@code trace_id} correlation with no error. <b>The Java transports live in separate
 * artifacts</b> ({@code babelqueue-java-sqs}, {@code babelqueue-java-redis}, {@code babelqueue-spring}),
 * so wiring this map onto each broker is a documented per-transport follow-up.
 */
@FunctionalInterface
public interface HeaderSender {

    /**
     * Sends one already-built envelope together with its out-of-band transport headers.
     *
     * @param envelope the envelope to publish (its {@code trace_id} encodes the producer trace)
     * @param headers  the out-of-band headers to carry beside the envelope (e.g. {@code traceparent});
     *                 never {@code null}, possibly empty when there is no active trace
     * @throws Exception to signal a failed publish (recorded on the producer span and re-thrown)
     */
    void send(Envelope envelope, Map<String, String> headers) throws Exception;
}
