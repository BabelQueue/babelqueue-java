package com.babelqueue.gdpr;

import com.babelqueue.JsonValues;
import com.babelqueue.schema.SensitivePath;
import com.babelqueue.schema.SensitivePaths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The RUNTIME half of ADR-0030: SDK-side field-level encryption of the {@code data} fields a
 * registry declared {@code x-gdpr-sensitive}. babelqueue-registry only DECLARES and AUDITS
 * sensitivity (and offers a one-way mask for safe logging); this class ENFORCES it on the wire — a
 * producer encrypts each marked leaf before publish, a consumer decrypts it after decode.
 *
 * <p>The contract mirrors the Go reference so every SDK round-trips byte-for-byte:
 * <ul>
 *   <li>The envelope stays <b>frozen</b> (GR-1). {@link #protect} mutates only <b>values inside
 *       {@code data}</b>: a sensitive leaf's value becomes a ciphertext <b>String</b>. It never
 *       adds, renames, removes or retypes an envelope field; {@code meta.schema_version} stays
 *       {@code 1}; {@code trace_id} is untouched (GR-4). {@code data} remains pure JSON (GR-3) — a
 *       JSON string is still pure JSON, so any SDK can carry the envelope even without the key (it
 *       just cannot read the protected fields). Which fields are sensitive lives in the schema, not
 *       the message, so nothing about the frame changes.</li>
 *   <li>Zero heavy dependencies (GR-7). The crypto is a caller-provided {@link Cipher} interface
 *       (KMS/Vault/HSM/tokenisation); the bundled {@link AesGcmCipher} is JDK-only. The core pulls
 *       no crypto/KMS dependency.</li>
 * </ul>
 *
 * <p>The sensitive paths come from the SAME per-URN schema the produce/consume validation path
 * already loads (via a {@code SchemaProvider}) — the {@code x-gdpr-sensitive} marks ride on it
 * ({@link SensitivePaths#of}). {@link #protect}/{@link #unprotect} are standalone helpers the caller
 * invokes, so the feature is strictly opt-in: a producer/consumer that never calls them behaves
 * exactly as before.
 *
 * <p>Typical wiring (producer), after building {@code data} and before encode/publish:
 * <pre>{@code
 * Map<String, Object> schema = provider.schemaFor(envelope.job());
 * if (schema != null) {
 *     SchemaValidation.validate(provider, envelope.job(), envelope.data()); // validate cleartext
 *     Gdpr.protect(envelope.data(), schema, cipher);                        // encrypt marked leaves
 * }
 * String body = EnvelopeCodec.encode(envelope);                            // ciphertext rides in data
 * }</pre>
 * and the inverse on the consumer, after decode and before the handler reads {@code data}:
 * <pre>{@code
 * Map<String, Object> schema = provider.schemaFor(in.job());
 * if (schema != null) {
 *     Gdpr.unprotect(in.data(), schema, cipher);                           // decrypt marked leaves
 * }
 * }</pre>
 *
 * <p>Validate cleartext BEFORE {@link #protect} / AFTER {@link #unprotect} — a schema that
 * constrains a sensitive field ({@code minLength}, {@code enum}, …) would reject the ciphertext
 * string otherwise.
 */
public final class Gdpr {

    private Gdpr() {
    }

    /**
     * Encrypts, in place, every value in {@code data} located at a path the schema marked
     * {@code x-gdpr-sensitive} — the producer-side step, run after building {@code data} and before
     * encode/publish. Each marked leaf's value is canonically JSON-encoded and replaced by
     * {@link Cipher#encrypt}'s ciphertext <b>String</b>; the envelope frame, non-sensitive fields,
     * and key order are untouched (GR-1).
     *
     * <p>A marked path that is absent from {@code data} is skipped (not an error) — schemas evolve
     * and a message need not carry every optional field. {@code data}/{@code schema}/{@code cipher}
     * being {@code null} is a no-op. A container mark (a whole object/array marked sensitive) is
     * supported: the entire sub-value is encoded and encrypted as one ciphertext string.
     *
     * <p>On any cipher failure it throws and leaves {@code data} in a partially-protected state; a
     * caller should treat a thrown {@code protect} as fatal for that message (do not publish it).
     *
     * @param data   the message data, mutated in place
     * @param schema the decoded JSON Schema carrying the {@code x-gdpr-sensitive} marks
     * @param cipher the caller's encryption primitive
     */
    public static void protect(Map<String, Object> data, Map<String, Object> schema, Cipher cipher) {
        walk(data, schema, cipher, Gdpr::encryptLeaf);
    }

    /**
     * The consumer-side inverse of {@link #protect}: decrypts, in place, every value in {@code data}
     * at an {@code x-gdpr-sensitive} path, restoring the original JSON value byte-for-byte. Run it
     * after decode and before the handler reads {@code data}.
     *
     * <p>An absent path is skipped. A leaf that is NOT a string — e.g. it was never protected, or
     * this is a re-run after a successful {@code unprotect} — is left as-is, so re-invoking
     * {@code unprotect} on already-cleartext data is safe (idempotent for non-string leaves). A
     * string the cipher cannot open (wrong key, tampered, or not a ciphertext) throws a
     * {@link DecryptException} — the consumer should fail the message (retry / dead-letter) rather
     * than process unreadable PII.
     *
     * @param data   the message data, mutated in place
     * @param schema the decoded JSON Schema carrying the {@code x-gdpr-sensitive} marks
     * @param cipher the caller's encryption primitive
     * @throws DecryptException when a protected field cannot be restored
     */
    public static void unprotect(Map<String, Object> data, Map<String, Object> schema, Cipher cipher) {
        walk(data, schema, cipher, Gdpr::decryptLeaf);
    }

    /**
     * Transforms a single sensitive leaf value (encrypt or decrypt). Returns a {@link LeafResult}:
     * either a new value to store, or a signal that the slot should be left untouched.
     */
    @FunctionalInterface
    private interface LeafOp {
        LeafResult apply(Object value, Cipher cipher) throws Exception;
    }

    /** The outcome of a {@link LeafOp}: replace the leaf with {@code value}, or leave it as-is. */
    private record LeafResult(Object value, boolean replace) {
        static final LeafResult SKIP = new LeafResult(null, false);

        static LeafResult of(Object value) {
            return new LeafResult(value, true);
        }
    }

    /**
     * Drives a {@link LeafOp} over every {@code x-gdpr-sensitive} path the schema declares. It
     * resolves each path against {@code data} itself (NOT by re-walking the schema over the value),
     * so the operation touches exactly the declared leaves and nothing else — non-sensitive siblings
     * are never read or copied.
     */
    private static void walk(Map<String, Object> data, Map<String, Object> schema, Cipher cipher, LeafOp op) {
        if (data == null || schema == null || cipher == null) {
            return;
        }
        for (SensitivePath sp : SensitivePaths.of(schema)) {
            applyAtPath(data, parsePath(sp.path()), 0, cipher, op);
        }
    }

    /**
     * One step of a sensitive path: a named object key, optionally with array-descent.
     * {@code "addresses[].line"} parses to {@code [{addresses, array=true}, {line}]}.
     */
    private record Segment(String key, boolean array) {
    }

    /**
     * Splits a {@link SensitivePath#path()} ({@code "email"}, {@code "profile.full_name"},
     * {@code "addresses[].line"}) into segments. The {@code "[]"} marker binds to the segment it
     * trails, signalling array descent. The empty/root path yields an empty list (a root mark is not
     * a meaningful data path for an envelope's data object, so it is skipped).
     */
    private static List<Segment> parsePath(String path) {
        List<Segment> segs = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return segs;
        }
        for (String part : path.split("\\.", -1)) {
            if (part.endsWith("[]")) {
                segs.add(new Segment(part.substring(0, part.length() - 2), true));
            } else {
                segs.add(new Segment(part, false));
            }
        }
        return segs;
    }

    /**
     * Resolves {@code segs} from index {@code at} against the current {@code node} and runs {@code op}
     * on the leaf(s). It descends objects by key and, when a segment is an array, fans out over every
     * element. An absent key or a type mismatch (a path that does not exist in this particular
     * message) is skipped silently — schemas describe the union of possible shapes; a given message
     * need not contain every field.
     */
    @SuppressWarnings("unchecked")
    private static void applyAtPath(Object node, List<Segment> segs, int at, Cipher cipher, LeafOp op) {
        if (at >= segs.size()) {
            return; // root mark or exhausted path with no leaf key — nothing addressable in data
        }
        if (!(node instanceof Map<?, ?> map)) {
            return; // expected an object here but the message has something else — skip
        }
        Map<String, Object> obj = (Map<String, Object>) map;

        Segment seg = segs.get(at);
        if (!obj.containsKey(seg.key())) {
            return; // absent field — skip (not an error)
        }
        Object child = obj.get(seg.key());
        boolean last = at == segs.size() - 1;

        if (seg.array()) {
            if (!(child instanceof List<?> list)) {
                return; // declared array but message has a non-array — skip
            }
            List<Object> arr = (List<Object>) list;
            for (int i = 0; i < arr.size(); i++) {
                if (last) {
                    LeafResult result = runLeaf(op, arr.get(i), cipher);
                    if (result.replace()) {
                        arr.set(i, result.value());
                    }
                } else {
                    applyAtPath(arr.get(i), segs, at + 1, cipher, op);
                }
            }
            return;
        }

        if (last) {
            LeafResult result = runLeaf(op, child, cipher);
            if (result.replace()) {
                obj.put(seg.key(), result.value());
            }
            return;
        }
        applyAtPath(child, segs, at + 1, cipher, op);
    }

    /** Invoke a leaf op, surfacing a checked failure as the unchecked {@link DecryptException}. */
    private static LeafResult runLeaf(LeafOp op, Object value, Cipher cipher) {
        try {
            return op.apply(value, cipher);
        } catch (DecryptException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DecryptException(ex.getMessage(), ex);
        }
    }

    /**
     * Canonically JSON-encodes one field value and replaces it with the cipher's ciphertext string.
     * The JSON encoding is what makes the round-trip exact: {@link #decryptLeaf}'s decode restores
     * the same decoded-JSON value ({@link Long}/{@link Double} for numbers, {@code Map} for objects,
     * …) the envelope codec would have produced, so protect&rarr;unprotect is byte-for-byte.
     */
    private static LeafResult encryptLeaf(Object value, Cipher cipher) throws Exception {
        byte[] plaintext = JsonValues.encode(value).getBytes(StandardCharsets.UTF_8);
        return LeafResult.of(cipher.encrypt(plaintext));
    }

    /**
     * Reverses {@link #encryptLeaf}. A non-string leaf is left untouched (so {@code unprotect} is
     * safe to re-run on already-cleartext data); a string that fails to open or to JSON-decode yields
     * a {@link DecryptException} so the consumer fails the message rather than handling unreadable
     * PII.
     */
    private static LeafResult decryptLeaf(Object value, Cipher cipher) {
        if (!(value instanceof String ciphertext)) {
            // Not a ciphertext string (already cleartext, or never protected) — leave as-is.
            return LeafResult.SKIP;
        }
        byte[] plaintext;
        try {
            plaintext = cipher.decrypt(ciphertext);
        } catch (Exception ex) {
            throw new DecryptException(ex.getMessage(), ex);
        }
        try {
            return LeafResult.of(JsonValues.decode(new String(plaintext, StandardCharsets.UTF_8)));
        } catch (RuntimeException ex) {
            throw new DecryptException("decoded plaintext is not JSON", ex);
        }
    }
}
