package com.babelqueue.gdpr;

/**
 * Thrown by {@link AesGcmCipher#AesGcmCipher(byte[])} when the key is not 16, 24, or 32 bytes
 * (AES-128/192/256). It is an {@link IllegalArgumentException} — a misconfigured key is a
 * programming/configuration error caught once at construction, not a per-message runtime failure —
 * so a caller need not declare it on the {@link Cipher} construction site.
 */
public class InvalidKeySizeException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * @param actualBytes the rejected key length, in bytes
     */
    public InvalidKeySizeException(int actualBytes) {
        super("babelqueue/gdpr: AES key must be 16, 24, or 32 bytes (got " + actualBytes + ")");
    }
}
