package com.babelqueue;

/**
 * One dead-letter the SDK itself carried out, handed to a {@link DeadLetterObserver} after the
 * write (or the SDK-initiated native dead-letter) was confirmed (ADR-0039 section 5.1).
 *
 * <p>The three fields are the logical event of the ADR. A {@code null} component is normalised to
 * the empty string, so an observer never has to null-check.
 *
 * @param queue           the source queue/topic the message was consumed from ({@code meta.queue},
 *                        else the consumer's configured queue); empty when unknown
 * @param deadLetterQueue the DLQ the record went to; empty for a native dead-letter (the broker's
 *                        own {@code $DeadLetterQueue} sub-entity)
 * @param reason          the {@code dead_letter.reason} the SDK wrote; empty for a raw body
 */
public record DeadLetterEvent(String queue, String deadLetterQueue, String reason) {

    /** Normalises {@code null} components to the empty string. */
    public DeadLetterEvent {
        queue = queue == null ? "" : queue;
        deadLetterQueue = deadLetterQueue == null ? "" : deadLetterQueue;
        reason = reason == null ? "" : reason;
    }
}
