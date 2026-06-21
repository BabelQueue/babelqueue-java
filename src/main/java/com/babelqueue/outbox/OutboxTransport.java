package com.babelqueue.outbox;

/**
 * The minimal publish-only broker seam the {@link OutboxRelay} forwards through — publish one
 * already-encoded envelope onto a queue. It is the byte-typed counterpart of the core's existing
 * transport surfaces ({@code Redrive.Transport.publish(queue, body)} and the {@code otel.Sender}):
 * the core is codec-only, so an adapter implements this over its broker
 * ({@code babelqueue-java-sqs}, {@code babelqueue-java-redis}, {@code babelqueue-spring}, or a
 * plain client).
 *
 * <p>The relay hands this method the <b>stored bytes verbatim</b> — the exact
 * {@link com.babelqueue.EnvelopeCodec#encode} output that {@link Outbox#write} persisted (GR-1):
 * the body is never decoded, rebuilt or re-encoded, so {@code trace_id} survives end-to-end (GR-4)
 * and the bytes that reach the broker are byte-identical to what was stored (GR-5). It is a
 * single-method {@link FunctionalInterface} so a caller can supply a lambda over their own client.
 */
@FunctionalInterface
public interface OutboxTransport {

    /**
     * Publish a raw, already-encoded (UTF-8 JSON) envelope onto a queue. The {@code body} is the
     * verbatim bytes the outbox stored; do not decode or mutate them.
     *
     * @param queue the logical queue to publish to
     * @param body  the frozen, encoded envelope bytes, byte-for-byte as stored
     * @throws Exception to signal a failed publish; the relay catches it, records it via
     *                   {@link OutboxStore#markFailed(String, String)} and leaves the row pending
     */
    void publish(String queue, byte[] body) throws Exception;
}
