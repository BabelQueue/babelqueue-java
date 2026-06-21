package com.babelqueue.outbox;

import java.util.List;

/**
 * The persistence seam for the <b>transactional outbox</b> (ADR-0029) — the durable
 * "outbox" table that an {@link Outbox} writer fills and an {@link OutboxRelay} drains.
 *
 * <p>The whole point of the pattern is to remove the <em>dual write</em> a plain producer
 * makes: "commit the business row" <b>and</b> "publish to the broker" are two systems that
 * can disagree on a crash. Instead the message is written <b>into the same database, inside
 * the same transaction</b> as the business data — so it commits or rolls back atomically with
 * it — and a separate relay publishes it afterwards. No distributed transaction, exactly-once
 * <em>handoff</em> into the broker (then at-least-once on the wire, as always).
 *
 * <p><b>The transaction boundary is the CALLER'S.</b> The core does not open, commit or roll
 * back anything: {@link #save(byte[], String)} is invoked from <em>inside</em> a transaction
 * the caller already began (around its own {@code INSERT INTO orders …}), and the caller
 * commits both together. This keeps the core free of any DB driver (GR-7): the core defines
 * this contract; a concrete adapter (e.g. a JDBC one) binds it to a real connection. The
 * reference {@link InMemoryOutboxStore} is for tests and single-process demos.
 *
 * <p>The stored value is the <b>frozen wire envelope, byte-for-byte unchanged</b> (GR-1): the
 * {@link com.babelqueue.EnvelopeCodec#encode(com.babelqueue.Envelope)} output as UTF-8 bytes.
 * The outbox adds its own bookkeeping columns (id, status, attempts, timestamps) <em>around</em>
 * the envelope; it never adds a field <em>to</em> it. What the relay publishes is the same bytes
 * that were stored — {@code trace_id} is preserved end-to-end (GR-4), byte-compatible (GR-5).
 */
public interface OutboxStore {

    /**
     * Persist one encoded envelope into the outbox, <b>within the transaction the caller has
     * already opened</b> around its business write. Returns the new row's outbox id (the store's
     * own primary key — NOT {@code meta.id}), which the caller may keep for correlation. The body
     * is stored verbatim; an implementation must not re-encode or mutate it.
     *
     * @param encoded the {@link com.babelqueue.EnvelopeCodec#encode} output as UTF-8 bytes
     * @param queue   the logical target queue, captured for the relay
     * @return the outbox row id
     */
    String save(byte[] encoded, String queue);

    /**
     * Reserve up to {@code limit} rows that are pending publish, <b>oldest first</b>, so a relay can
     * forward them. Implementations SHOULD lock/claim the rows they return (e.g.
     * {@code SELECT … FOR UPDATE SKIP LOCKED}, or a {@code picked_at} claim) so two concurrent
     * relays do not both publish the same row; at-least-once still tolerates a rare double send.
     * That claim/lock is the adapter's responsibility — the in-memory reference does not implement
     * it.
     *
     * @param limit maximum rows to return (a positive batch size)
     * @return pending rows, oldest first; empty when the outbox is drained
     */
    List<OutboxRecord> fetchUnpublished(int limit);

    /**
     * Mark the given outbox rows as successfully published (so they are never relayed again).
     * Called by the relay only <b>after</b> the transport accepted the message.
     *
     * @param ids outbox row ids previously returned by {@link #fetchUnpublished(int)}
     */
    void markPublished(List<String> ids);

    /**
     * Record a failed publish attempt for one row: increment its attempt counter and store the last
     * error, leaving it pending so a later relay pass retries it (at-least-once). A store MAY move a
     * row that exceeds a max-attempts threshold to a terminal/parked state, but that policy is the
     * adapter's, not the core's.
     *
     * @param id    the outbox row id
     * @param error a short, human-readable failure reason (never secrets)
     */
    void markFailed(String id, String error);
}
