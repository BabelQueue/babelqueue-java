package com.babelqueue.gdpr;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A reference {@link Cipher} built ONLY on the JDK's {@code javax.crypto} ({@code AES/GCM/NoPadding}):
 * AES-GCM authenticated encryption with a fresh random 12-byte IV per call, the IV <b>prepended</b>
 * to the ciphertext, the whole thing Base64-encoded so it drops straight into a JSON string. The key
 * is the CALLER's — this type performs no key management, rotation or derivation; bind a KMS-backed
 * {@link Cipher} for that.
 *
 * <p>A 32-byte key selects AES-256-GCM (recommended); 24- and 16-byte keys select AES-192/128-GCM.
 * GCM authenticates the ciphertext, so {@link #decrypt} rejects any tampered or wrong-key input by
 * throwing (it never returns corrupt plaintext). It pulls no third-party crypto dependency (GR-7)
 * and is safe for concurrent use — {@code javax.crypto.Cipher} instances are created per call, and
 * the stored key material is only read.
 */
public final class AesGcmCipher implements Cipher {

    /** AES-GCM standard nonce/IV length, in bytes. A 12-byte IV is the recommended GCM size. */
    private static final int IV_LENGTH = 12;

    /** GCM authentication-tag length, in bits (the full 128-bit tag). */
    private static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * Build an AES-GCM reference cipher from a raw symmetric key. The key length selects the AES
     * variant: 32 bytes &rarr; AES-256-GCM (recommended), 24 &rarr; AES-192, 16 &rarr; AES-128.
     *
     * @param keyBytes the raw symmetric key (16, 24, or 32 bytes)
     * @throws InvalidKeySizeException if the key is not 16, 24, or 32 bytes
     */
    public AesGcmCipher(byte[] keyBytes) {
        int len = keyBytes == null ? 0 : keyBytes.length;
        if (len != 16 && len != 24 && len != 32) {
            throw new InvalidKeySizeException(len);
        }
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * Seals {@code plaintext} with a fresh random IV, prepends the IV, and Base64-encodes the result
     * ({@code Base64(iv || ciphertext || tag)}).
     */
    @Override
    public String encrypt(byte[] plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);

        javax.crypto.Cipher gcm = javax.crypto.Cipher.getInstance(TRANSFORMATION);
        gcm.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] sealed = gcm.doFinal(plaintext);

        byte[] out = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(sealed, 0, out, iv.length, sealed.length);
        return Base64.getEncoder().encodeToString(out);
    }

    /**
     * Reverses {@link #encrypt}: Base64-decodes, splits off the prepended IV, and opens the GCM
     * ciphertext. A wrong key or tampered input fails GCM authentication and throws (never corrupt
     * plaintext).
     *
     * @throws MalformedCiphertextException if the input is not valid Base64 or is too short to hold
     *                                      an IV (i.e. not something this cipher produced)
     * @throws GeneralSecurityException     if GCM authentication fails (wrong key or tampering)
     */
    @Override
    public byte[] decrypt(String ciphertext) throws GeneralSecurityException {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(ciphertext.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            throw new MalformedCiphertextException("not valid Base64", ex);
        }
        if (raw.length < IV_LENGTH) {
            throw new MalformedCiphertextException("shorter than the IV", null);
        }

        byte[] iv = Arrays.copyOfRange(raw, 0, IV_LENGTH);
        byte[] sealed = Arrays.copyOfRange(raw, IV_LENGTH, raw.length);

        javax.crypto.Cipher gcm = javax.crypto.Cipher.getInstance(TRANSFORMATION);
        gcm.init(javax.crypto.Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        // AEADBadTagException (a GeneralSecurityException) on wrong key / tampered input.
        return gcm.doFinal(sealed);
    }
}
