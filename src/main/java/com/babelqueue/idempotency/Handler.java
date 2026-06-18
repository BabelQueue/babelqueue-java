package com.babelqueue.idempotency;

import com.babelqueue.Envelope;

/**
 * A consume handler: processes one decoded {@link Envelope}. It may throw — a thrown
 * handler leaves the message unacknowledged so the runtime redelivers it (the core is
 * codec-only, so an adapter drives the actual consume loop).
 */
@FunctionalInterface
public interface Handler {

    /**
     * Handles one message.
     *
     * @param envelope the decoded envelope
     * @throws Exception to signal failure (redelivery / dead-letter per the adapter)
     */
    void handle(Envelope envelope) throws Exception;
}
