package com.babelqueue.outbox;

/**
 * One pending row read back from an {@link OutboxStore} for the {@link OutboxRelay} to publish.
 * It pairs the store's own bookkeeping ({@code id}, {@code attempts}) with the verbatim, frozen
 * wire envelope ({@code body}) and the queue it should go to.
 *
 * <p>{@code body} is the exact {@link com.babelqueue.EnvelopeCodec#encode} output (UTF-8 bytes)
 * that was handed to {@link OutboxStore#save(byte[], String)} — the relay publishes these bytes
 * unchanged (GR-1/GR-5), so {@code trace_id} is preserved end-to-end (GR-4) without the relay
 * ever decoding or rebuilding the envelope.
 *
 * @param id       the outbox row id (the store's primary key, not {@code meta.id})
 * @param body     the frozen, encoded envelope bytes, byte-for-byte as stored
 * @param queue    the logical queue the relay should publish to
 * @param attempts how many times the relay has already tried to publish this row
 */
public record OutboxRecord(String id, byte[] body, String queue, int attempts) {

    /** A freshly stored record the relay has not yet attempted. */
    public OutboxRecord(String id, byte[] body, String queue) {
        this(id, body, queue, 0);
    }
}
