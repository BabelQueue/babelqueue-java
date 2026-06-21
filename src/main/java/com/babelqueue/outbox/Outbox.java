package com.babelqueue.outbox;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import com.babelqueue.Meta;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The <b>write side</b> of the transactional outbox (ADR-0029): turn a BabelQueue
 * {@link Envelope} into a stored outbox row, so the message is persisted <em>atomically with
 * the business data</em> and a separate {@link OutboxRelay} publishes it later.
 *
 * <p>Usage — the caller owns the transaction boundary (this is the whole point):
 *
 * <pre>{@code
 * connection.setAutoCommit(false);
 * try {
 *     insertOrder(connection, order);                 // the business write
 *     Envelope env = EnvelopeCodec.make("urn:babel:orders:created", data, "orders", null);
 *     outbox.write(env);                              // same connection, same tx
 *     connection.commit();                            // both, or neither
 * } catch (Exception e) {
 *     connection.rollback();
 *     throw e;
 * }
 * }</pre>
 *
 * <p>Because both writes share one transaction, a crash can never leave the business row
 * committed without its message (the classic dual-write bug) — they commit or roll back together.
 * The handoff to the broker becomes a <em>local</em> problem the relay solves.
 *
 * <p>This helper is intentionally tiny and dependency-free: it only encodes via the frozen
 * {@link EnvelopeCodec} (GR-1 — the envelope bytes are stored unchanged; the outbox never adds an
 * envelope field) and delegates persistence to the injected {@link OutboxStore}, which the caller
 * binds to their own DB (GR-7). It does <b>not</b> begin or commit anything.
 */
public final class Outbox {

    /** The fallback queue when an envelope carries no {@code meta.queue}. */
    public static final String DEFAULT_QUEUE = "default";

    private final OutboxStore store;

    /**
     * @param store where {@link #write(Envelope)} persists rows; the caller binds it to their own DB
     */
    public Outbox(OutboxStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Encode the envelope (frozen codec, bytes unchanged) and persist it via the store, inside the
     * transaction the caller has already opened. Returns the new outbox row id.
     *
     * <p>It captures the target queue from {@code meta.queue} (falling back to {@code "default"}) at
     * write time, so the relay can publish to the right queue without ever decoding the body.
     *
     * @param envelope a canonical envelope from {@link EnvelopeCodec#make} / {@link EnvelopeCodec#fromMessage}
     * @return the outbox row id (for the caller's own correlation, if wanted)
     */
    public String write(Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        byte[] encoded = EnvelopeCodec.encode(envelope).getBytes(StandardCharsets.UTF_8);
        return store.save(encoded, queueOf(envelope));
    }

    /**
     * The logical queue the message targets: its {@code meta.queue}, falling back to "default".
     * Captured at write time so the relay can publish to the right queue without decoding the body.
     */
    private static String queueOf(Envelope envelope) {
        Meta meta = envelope.meta();
        if (meta != null && meta.queue() != null && !meta.queue().isBlank()) {
            return meta.queue();
        }
        return DEFAULT_QUEUE;
    }
}
