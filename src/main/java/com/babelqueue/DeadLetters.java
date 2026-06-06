package com.babelqueue;

/**
 * Builds the additive {@code dead_letter} block for an {@link Envelope}.
 *
 * @see DeadLetter
 */
public final class DeadLetters {

    private DeadLetters() {}

    /** Annotate using the envelope's current {@code attempts} and no error detail. */
    public static Envelope annotate(Envelope envelope, String reason, String originalQueue) {
        return annotate(envelope, reason, originalQueue, envelope.attempts(), null, null);
    }

    /**
     * Return a copy of the envelope with a {@link DeadLetter} block attached,
     * recording why and where it failed. The original envelope is preserved
     * unchanged inside the result (records are immutable), so any-language consumers
     * can still read it.
     */
    public static Envelope annotate(
        Envelope envelope,
        String reason,
        String originalQueue,
        int attempts,
        String error,
        String exception) {

        DeadLetter deadLetter = new DeadLetter(
            reason,
            error,
            exception,
            System.currentTimeMillis(),
            originalQueue,
            attempts,
            EnvelopeCodec.SOURCE_LANG);

        return new Envelope(
            envelope.job(),
            envelope.traceId(),
            envelope.data(),
            envelope.meta(),
            envelope.attempts(),
            deadLetter);
    }
}
