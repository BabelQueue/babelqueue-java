package com.babelqueue;

/**
 * The additive block appended to an {@link Envelope} when a message is
 * dead-lettered. The original envelope is preserved unchanged alongside it, so a
 * consumer in any language can still read the original job, data and trace id.
 *
 * @param reason        why the message was dead-lettered
 * @param error         a human-readable error message, or {@code null}
 * @param exception     the originating exception type/class name, or {@code null}
 * @param failedAt      failure time in Unix milliseconds, UTC
 * @param originalQueue the queue the message was consumed from
 * @param attempts      how many delivery attempts were made
 * @param lang          the SDK language that dead-lettered the message
 */
public record DeadLetter(
    String reason,
    String error,
    String exception,
    long failedAt,
    String originalQueue,
    int attempts,
    String lang
) {}
