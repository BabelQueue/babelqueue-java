package com.babelqueue.gdpr;

import com.babelqueue.BabelQueueException;

/**
 * Raised by {@link Gdpr#unprotect} when a protected field cannot be restored on the consume side —
 * a wrong key, a tampered/garbled ciphertext, or a value that is not the string {@link Gdpr#protect}
 * produced. {@code unprotect} stops at the first such failure and throws this, so it is
 * distinguishable from a missing field (which is skipped, not an error) and from an already-cleartext
 * non-string leaf (which is left untouched).
 *
 * <p>A consumer should treat it as fatal for that message: fail the delivery so the adapter retries
 * and eventually dead-letters it, rather than handle unreadable PII. It is unchecked
 * ({@link BabelQueueException} is a {@link RuntimeException}) so it composes with the existing
 * handler/redrive flow that already reacts to thrown runtime exceptions.
 */
public class DecryptException extends BabelQueueException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message the failure detail
     * @param cause   the underlying cipher or decode failure
     */
    public DecryptException(String message, Throwable cause) {
        super("babelqueue/gdpr: cannot decrypt a protected field: " + message, cause);
    }
}
