package com.babelqueue.otel;

import com.babelqueue.Envelope;

/**
 * Performs the actual transport write for {@link Tracing#publish}. The core is codec-only, so
 * the producer helper builds the envelope (stamping the active trace's id into its
 * {@code trace_id}) and hands it to a {@code Sender} that writes it to a broker.
 */
@FunctionalInterface
public interface Sender {

    /**
     * Sends one already-built envelope to its destination.
     *
     * @param envelope the envelope to publish
     * @throws Exception to signal a failed publish (recorded on the producer span and re-thrown)
     */
    void send(Envelope envelope) throws Exception;
}
