/**
 * Optional transactional-outbox helper (ADR-0029): the <b>producer-side</b> mirror of the
 * consumer-side idempotency helper (ADR-0022).
 *
 * <p>It removes the producer <em>dual write</em> — "commit the business row" <b>and</b> "publish to
 * the broker" are two systems that can disagree on a crash. The {@link com.babelqueue.outbox.Outbox}
 * writer persists the {@link com.babelqueue.EnvelopeCodec}-encoded envelope <b>into the caller's own
 * database, inside the caller's own transaction</b>, so it commits or rolls back atomically with the
 * business data; the {@link com.babelqueue.outbox.OutboxRelay} then publishes the durable rows
 * through the {@link com.babelqueue.outbox.OutboxTransport} seam afterwards. Exactly-once
 * <em>handoff</em> into the broker, then at-least-once on the wire as always.
 *
 * <p>The pieces:
 * <ul>
 *   <li>{@link com.babelqueue.outbox.OutboxStore} — the persistence contract the caller binds to
 *       their DB (the core adds <b>no</b> DB driver, GR-7); {@link com.babelqueue.outbox.InMemoryOutboxStore}
 *       is the reference for tests/demos.</li>
 *   <li>{@link com.babelqueue.outbox.Outbox} — {@code write(envelope)}: encode via the frozen codec
 *       (bytes unchanged, GR-1) and {@code save}. <b>The transaction boundary is the caller's</b>;
 *       this never begins/commits.</li>
 *   <li>{@link com.babelqueue.outbox.OutboxRelay} — {@code flush()}/{@code drain(maxPasses)}:
 *       publish the <b>stored bytes verbatim</b>, mark published only after the transport accepts,
 *       {@code markFailed} + bounded linear backoff on a throw (row stays pending, batch continues).</li>
 * </ul>
 *
 * <p>The wire envelope is unchanged ({@code schema_version: 1}) — the outbox stores it byte-for-byte
 * and never adds an envelope field. See {@code .ssot/architecture/adr/0029-transactional-outbox-helper.md}.
 */
package com.babelqueue.outbox;
