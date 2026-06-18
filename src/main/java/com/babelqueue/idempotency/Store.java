package com.babelqueue.idempotency;

/**
 * A pluggable record of message ids that have already been processed, keyed on the
 * envelope's {@code meta.id}. The reference {@link InMemoryStore} is for tests and
 * single-process consumers; production backends (Redis, a database table) implement the
 * same three methods.
 *
 * <p>The contract is "seen-set" dedupe: it answers <em>"was this id processed?"</em>, not
 * <em>"what did it return"</em> — queue handlers have no response to replay. It provides
 * post-success dedupe under at-least-once delivery with idempotent handlers, not
 * exactly-once and not in-flight concurrency locking. A transactional / outbox mode is a
 * documented future direction (ADR-0022).
 */
public interface Store {

    /** Whether this message id has already been processed (remembered). */
    boolean seen(String messageId);

    /** Records this message id as processed. */
    void remember(String messageId);

    /** Drops an id from the store (manual eviction; a backend may also expire ids). */
    void forget(String messageId);
}
