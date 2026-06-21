package com.babelqueue.gdpr;

/**
 * The field-level protection primitive that the <b>caller provides</b> — a seam onto a KMS, a
 * Vault transit engine, an HSM, a tokenisation service, or the reference {@link AesGcmCipher}
 * below. {@link Gdpr#protect protect} runs {@link #encrypt} over every {@code x-gdpr-sensitive}
 * leaf's value (after it is canonically JSON-encoded); {@link Gdpr#unprotect unprotect} runs
 * {@link #decrypt} to restore it. Keeping this an interface is what holds GR-7: the core never
 * pulls a crypto/KMS dependency — only a caller who binds a concrete backend does.
 *
 * <p>Contract for an implementation:
 * <ul>
 *   <li>{@link #encrypt} takes the canonical JSON bytes of one field value (see
 *       {@link Gdpr#protect}) and returns the ciphertext as a <b>String</b> that is valid for
 *       placement inside a JSON document (the {@link AesGcmCipher} reference returns Base64, which
 *       is). The same plaintext MAY encrypt to a different string each call — a random nonce/IV is
 *       expected and good.</li>
 *   <li>{@link #decrypt} is the exact inverse: given a string {@code encrypt} produced, it returns
 *       the original JSON bytes <b>byte-for-byte</b>. A string it did not produce, or one produced
 *       under a different key, MUST throw rather than return silent garbage, so a wrong-key consume
 *       fails loudly (the message then takes retry / dead-letter).</li>
 *   <li>Both MUST be safe for concurrent use; a producer/consumer fans the same {@code Cipher}
 *       across threads.</li>
 * </ul>
 */
public interface Cipher {

    /**
     * Protects one field value (its canonical JSON bytes) and returns a JSON-safe ciphertext
     * string.
     *
     * @param plaintext the canonical JSON bytes of one field value
     * @return the ciphertext, encoded so it is safe to place inside a JSON string
     * @throws Exception if encryption fails (surfaced wrapped by {@link Gdpr#protect})
     */
    String encrypt(byte[] plaintext) throws Exception;

    /**
     * Reverses {@link #encrypt}, returning the original field-value JSON bytes.
     *
     * @param ciphertext a string produced by {@link #encrypt}
     * @return the original field-value JSON bytes
     * @throws Exception if the input is not a valid ciphertext, is tampered, or was produced under
     *                   a different key (surfaced as {@link DecryptException} by
     *                   {@link Gdpr#unprotect})
     */
    byte[] decrypt(String ciphertext) throws Exception;
}
