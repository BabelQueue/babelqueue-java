package com.babelqueue.gdpr;

import java.security.GeneralSecurityException;

/**
 * Thrown by {@link AesGcmCipher#decrypt(String)} when the input is not valid Base64 or is too
 * short to contain an IV — i.e. not something this cipher produced. It extends
 * {@link GeneralSecurityException} so it fits the {@link Cipher} contract's checked-exception
 * surface, and {@link Gdpr#unprotect} surfaces it (like any decrypt failure) as a
 * {@link DecryptException}.
 */
public class MalformedCiphertextException extends GeneralSecurityException {

    private static final long serialVersionUID = 1L;

    /**
     * @param reason why the ciphertext is malformed
     * @param cause  the underlying cause, or {@code null}
     */
    public MalformedCiphertextException(String reason, Throwable cause) {
        super("babelqueue/gdpr: malformed ciphertext: " + reason, cause);
    }
}
