package com.babelqueue;

/**
 * Canonical encode/decode for a <b>single</b> decoded-JSON value, using the same minimal codec
 * the wire envelope uses. It is the seam field-level features ({@link com.babelqueue.gdpr})
 * need: the core's {@link Json} reader/writer is package-private (it must never force a JSON
 * library on consumers, GR-7), so this class exposes just enough of it — one value in, one value
 * out — without widening {@code Json} itself.
 *
 * <p>Because it routes through the very same codec, the round-trip is <b>type-exact</b>: a value
 * decoded by {@link EnvelopeCodec#decode} (numbers as {@link Long}/{@link java.math.BigInteger}/
 * {@link Double}, objects as {@link java.util.LinkedHashMap}, arrays as {@link java.util.ArrayList})
 * re-encodes and re-decodes back to the same Java types. That is what lets
 * {@link com.babelqueue.gdpr.Gdpr#protect protect}/{@link com.babelqueue.gdpr.Gdpr#unprotect unprotect}
 * restore a protected field byte-for-byte after a decrypt.
 */
public final class JsonValues {

    private JsonValues() {
    }

    /**
     * Encode one decoded-JSON value to its compact canonical JSON string — the same form the
     * envelope codec emits (slashes and non-ASCII left literal, no insignificant whitespace).
     *
     * @param value a decoded-JSON value ({@code Map}/{@code List}/{@code String}/{@code Number}/
     *              {@code Boolean}/{@code null})
     * @return the compact JSON encoding
     * @throws BabelQueueException if the value cannot be encoded (e.g. a non-finite number)
     */
    public static String encode(Object value) {
        return Json.write(value);
    }

    /**
     * Parse one JSON document back into a decoded-JSON value, with the same types the envelope
     * codec produces. The exact inverse of {@link #encode(Object)}.
     *
     * @param raw a JSON document holding a single value
     * @return the decoded value
     * @throws BabelQueueException if {@code raw} is not a single, well-formed JSON value
     */
    public static Object decode(String raw) {
        return Json.parse(raw);
    }
}
