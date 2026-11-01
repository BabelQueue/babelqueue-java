package com.babelqueue;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional observer of dead-letters the SDK itself carried out (ADR-0039 section 5.1).
 *
 * <p>The core is codec-only and runs no consume loop, so it does not call the observer itself: a
 * transport consumer that writes a record to the BabelQueue DLQ (or itself initiates a native
 * dead-letter) accepts an optional {@code DeadLetterObserver} and calls it <b>once per
 * dead-letter, after the write is confirmed</b>, through {@link #notifySafely}. It is not called
 * when the write fails, when the broker dead-letters on its own, or when DLQ writing is disabled.
 *
 * <p>The observer must never change the settle outcome: {@link #notifySafely} catches and logs
 * anything the observer throws, so no ack, nack or retry is skipped or added because of it.
 *
 * <p>{@code com.babelqueue.otel.Metrics#deadLetterObserver} is the OpenTelemetry implementation
 * (it increments {@code babelqueue.dlq.messages}); logging or alerting hooks can implement this
 * interface too.
 */
@FunctionalInterface
public interface DeadLetterObserver {

    /**
     * Called once for a confirmed dead-letter.
     *
     * @param event the dead-letter that was just written
     */
    void onDeadLetter(DeadLetterEvent event);

    /**
     * Calls {@code observer} with {@code event}, isolating the caller from the observer: a
     * {@code null} observer is a no-op, and anything else the observer throws (an exception or an
     * {@link Error} such as {@link AssertionError} or {@link LinkageError}) is logged (at {@code WARNING})
     * and swallowed. A {@link VirtualMachineError} (out of memory, stack overflow) is rethrown.
     *
     * @param observer the observer, or {@code null} for none
     * @param event    the confirmed dead-letter
     */
    static void notifySafely(DeadLetterObserver observer, DeadLetterEvent event) {
        if (observer == null) {
            return;
        }
        try {
            observer.onDeadLetter(event);
        } catch (Throwable t) { // NOPMD - ADR-0039 section 5.1: an observer error or panic must never reach the settle path
            if (t instanceof VirtualMachineError) {
                throw (VirtualMachineError) t;
            }
            Logger.getLogger(DeadLetterObserver.class.getName())
                .log(Level.WARNING, "dead-letter observer failed; the settle outcome is unchanged", t);
        }
    }
}
