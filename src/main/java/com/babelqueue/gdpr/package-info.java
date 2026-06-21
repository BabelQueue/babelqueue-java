/**
 * Optional runtime GDPR field encryption (ADR-0030): the <b>SDK-enforcement</b> half of the
 * registry's {@code x-gdpr-sensitive} declaration.
 *
 * <p>babelqueue-registry only DECLARES and AUDITS which fields are personal data (and offers a
 * one-way mask for safe logging). This package ENFORCES it on the wire: a producer encrypts each
 * marked {@code data} leaf before publish ({@link com.babelqueue.gdpr.Gdpr#protect}), a consumer
 * decrypts it after decode ({@link com.babelqueue.gdpr.Gdpr#unprotect}). It is the Java mirror of
 * the Go {@code gdpr} reference, so every SDK round-trips byte-for-byte.
 *
 * <p>The pieces:
 * <ul>
 *   <li>{@link com.babelqueue.gdpr.Cipher} — the caller-provided encryption primitive (a seam onto
 *       KMS/Vault/HSM/tokenisation); keeping it an interface holds GR-7 (the core pulls no crypto
 *       dependency). {@link com.babelqueue.gdpr.AesGcmCipher} is the JDK-only reference (AES-GCM via
 *       {@code javax.crypto}, random 12-byte IV prepended, Base64).</li>
 *   <li>{@link com.babelqueue.gdpr.Gdpr} — {@code protect}/{@code unprotect}: standalone, opt-in
 *       helpers that rewrite each marked leaf in place. The sensitive paths come from the same
 *       per-URN schema the validation path already loads
 *       ({@link com.babelqueue.schema.SensitivePaths}).</li>
 * </ul>
 *
 * <p>The wire envelope stays <b>frozen</b> (GR-1): only <b>values inside {@code data}</b> change — a
 * sensitive leaf's value becomes a ciphertext string, {@code data} remains pure JSON (GR-3),
 * {@code meta.schema_version} stays {@code 1} and {@code trace_id} is untouched (GR-4). Validate
 * cleartext BEFORE {@code protect} / AFTER {@code unprotect}. See
 * {@code .ssot/architecture/adr/0030-gdpr-sensitive-field-governance.md}.
 */
package com.babelqueue.gdpr;
