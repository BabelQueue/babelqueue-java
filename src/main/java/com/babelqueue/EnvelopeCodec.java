package com.babelqueue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Builds, encodes and decodes the canonical BabelQueue envelope — the single Java
 * implementation of the wire format. The shape is frozen as
 * {@code {job, trace_id, data, meta, attempts}} (schema version 1) so a Java
 * service interoperates byte-for-byte with the PHP/Laravel, Python, Go and Node
 * SDKs over any broker. Pure JDK — no dependencies.
 *
 * <p>Full spec: <a href="https://babelqueue.com">babelqueue.com</a>
 */
public final class EnvelopeCodec {

    /** The wire envelope schema version this core implements. */
    public static final int SCHEMA_VERSION = 1;

    /** The value stamped into {@code meta.lang} for envelopes produced here. */
    public static final String SOURCE_LANG = "java";

    /** Top-level keys the codec maps to {@link Envelope} components ({@code urn} is the inbound alias of {@code job}). */
    private static final Set<String> KNOWN_TOP_LEVEL =
        Set.of("job", "urn", "trace_id", "data", "meta", "attempts", "dead_letter");

    /** {@code meta} keys the codec maps to {@link Meta} components. */
    private static final Set<String> KNOWN_META =
        Set.of("id", "queue", "lang", "schema_version", "created_at");

    /**
     * Core top-level keys the codec itself writes — an extras entry with one of these names
     * would shadow a contract field, so encode rejects it (ADR-0031 (b)).
     */
    private static final Set<String> CORE_TOP_LEVEL =
        Set.of("job", "trace_id", "data", "meta", "attempts", "dead_letter");

    /**
     * Forbidden top-level keys (message-envelope.md §10) — never captured on decode, rejected on
     * encode. {@code urn} is the inbound alias of {@code job}: decode resolves it and never
     * retains it, so on the encode side it can only be a leftover (ADR-0031 (c)).
     */
    private static final Set<String> FORBIDDEN_TOP_LEVEL = Set.of("timestamp", "urn");

    /** Forbidden {@code meta} keys (message-envelope.md §10) — never captured, never encoded. */
    private static final Set<String> FORBIDDEN_META =
        Set.of("max_retries", "attempts", "source", "ts");

    private static final System.Logger LOGGER = System.getLogger(EnvelopeCodec.class.getName());

    private EnvelopeCodec() {}

    /** Build the canonical envelope for a {@code (urn, data)} pair on the default queue. */
    public static Envelope make(String urn, Map<String, Object> data) {
        return make(urn, data, "default", null);
    }

    /**
     * Build the canonical envelope for a {@code (urn, data)} pair. A fresh trace id
     * is minted unless {@code traceId} is non-blank (trace continuation); {@code
     * attempts} starts at 0 and {@code meta} is stamped with a unique id, the source
     * language, the schema version and a millisecond timestamp.
     *
     * @throws BabelQueueException if {@code urn} is {@code null} or blank
     */
    public static Envelope make(String urn, Map<String, Object> data, String queue, String traceId) {
        return make(urn, data, queue, traceId, null);
    }

    /**
     * Same as {@link #make(String, Map, String, String)}, with extra {@code meta} entries
     * written next to the core keys (ADR-0031 (b)). Each entry is one flat {@code meta} key; a
     * vendor block is supplied as the whole {@code ext} value (recommended shape:
     * {@code meta.ext.<vendor>}). The core never overwrites a core key.
     *
     * @param meta extra {@code meta} entries, or {@code null} for none
     * @throws BabelQueueException if {@code urn} is {@code null} or blank, or if {@code meta}
     *     contains a core {@code meta} key ({@code id}, {@code queue}, {@code lang},
     *     {@code schema_version}, {@code created_at}), a forbidden key (message-envelope.md §10)
     *     or a {@code null} key
     */
    public static Envelope make(
        String urn, Map<String, Object> data, String queue, String traceId, Map<String, Object> meta) {
        Map<String, Object> metaExtras = meta == null ? null : new LinkedHashMap<>(meta);
        rejectInvalidExtras(metaExtras, KNOWN_META, FORBIDDEN_META, "/meta/");

        String resolvedUrn = urn == null ? "" : stripAscii(urn);
        if (resolvedUrn.isEmpty()) {
            throw new BabelQueueException(
                "A polyglot message must expose a stable, non-empty URN so consumers "
                    + "can identify it without any class name.");
        }

        String trace = traceId == null ? "" : stripAscii(traceId);
        if (trace.isEmpty()) {
            trace = UUID.randomUUID().toString();
        }

        Map<String, Object> payload =
            data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);

        Meta metaBlock = new Meta(
            UUID.randomUUID().toString(),
            queue == null ? "default" : queue,
            SOURCE_LANG,
            SCHEMA_VERSION,
            System.currentTimeMillis(),
            metaExtras);

        return new Envelope(resolvedUrn, trace, payload, metaBlock, 0, null);
    }

    /** Build the envelope from a {@link PolyglotMessage} on the default queue. */
    public static Envelope fromMessage(PolyglotMessage message) {
        return fromMessage(message, "default");
    }

    /**
     * Build the envelope from a {@link PolyglotMessage}. If the message also
     * implements {@link HasTraceId} and returns a non-empty value, that trace id is
     * reused.
     */
    public static Envelope fromMessage(PolyglotMessage message, String queue) {
        String trace = message instanceof HasTraceId hasTrace ? hasTrace.getBabelTraceId() : null;
        return make(message.getBabelUrn(), message.toPayload(), queue, trace);
    }

    /**
     * Encode the envelope as compact UTF-8 JSON. Slashes and non-ASCII are left
     * unescaped, matching the other SDK cores; the field order is canonical. Known
     * fields are written first in their canonical order, followed by the unknown keys
     * kept in {@link Envelope#extras()} / {@link Meta#extras()} in their original order.
     *
     * @throws BabelQueueException if an extras entry is a forbidden key (§10) or collides with a
     *     core key — never a silent drop (ADR-0031 (c)). Decode removes forbidden keys, so a
     *     decoded message re-encodes without tripping this.
     */
    public static String encode(Envelope envelope) {
        rejectInvalidExtras(envelope.extras(), CORE_TOP_LEVEL, FORBIDDEN_TOP_LEVEL, "/");
        if (envelope.meta() != null) {
            rejectInvalidExtras(envelope.meta().extras(), KNOWN_META, FORBIDDEN_META, "/meta/");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("job", envelope.job());
        root.put("trace_id", envelope.traceId());
        root.put("data", envelope.data());

        Meta meta = envelope.meta();
        Map<String, Object> metaMap = new LinkedHashMap<>();
        if (meta != null) {
            metaMap.put("id", meta.id());
            metaMap.put("queue", meta.queue());
            metaMap.put("lang", meta.lang());
            metaMap.put("schema_version", meta.schemaVersion());
            metaMap.put("created_at", meta.createdAt());
            appendExtras(metaMap, meta.extras());
        }
        root.put("meta", metaMap);
        root.put("attempts", envelope.attempts());

        DeadLetter dl = envelope.deadLetter();
        if (dl != null) {
            Map<String, Object> dlMap = new LinkedHashMap<>();
            dlMap.put("reason", dl.reason());
            dlMap.put("error", dl.error());
            dlMap.put("exception", dl.exception());
            dlMap.put("failed_at", dl.failedAt());
            dlMap.put("original_queue", dl.originalQueue());
            dlMap.put("attempts", dl.attempts());
            dlMap.put("lang", dl.lang());
            root.put("dead_letter", dlMap);
        }

        appendExtras(root, envelope.extras());
        return Json.write(root);
    }

    private static void appendExtras(Map<String, Object> target, Map<String, Object> extras) {
        if (extras != null) {
            target.putAll(extras);
        }
    }

    /**
     * Reject an extras map that holds a {@code null} key, a core key or a forbidden key.
     * {@code pointerPrefix} turns the key into the JSON pointer named in the message.
     */
    private static void rejectInvalidExtras(
        Map<String, Object> extras, Set<String> core, Set<String> forbidden, String pointerPrefix) {
        if (extras == null) {
            return;
        }
        for (String key : extras.keySet()) {
            if (key == null) {
                throw new BabelQueueException("BabelQueue: an extension key must not be null");
            }
            if (forbidden.contains(key)) {
                throw new BabelQueueException(
                    "BabelQueue: cannot encode forbidden envelope key " + pointerPrefix + key
                        + " (message-envelope.md §10)");
            }
            if (core.contains(key)) {
                throw new BabelQueueException(
                    "BabelQueue: extension key " + pointerPrefix + key
                        + " collides with a core envelope key and would overwrite it");
            }
        }
    }

    /**
     * Parse a raw JSON body into an {@link Envelope}. Malformed or non-object input
     * yields an empty envelope (so {@link #accepts} returns {@code false}); the
     * {@code urn} inbound alias is resolved into {@code job}. Does not validate the
     * contents — call {@link #accepts} first.
     *
     * <p>Unknown top-level and {@code meta} keys are preserved in the envelope's
     * extras. Forbidden keys (message-envelope.md §10) are dropped and a warning is
     * logged via {@link System.Logger} at {@code WARNING} level; use
     * {@link #decode(String, Consumer)} to receive the warnings directly.
     */
    public static Envelope decode(String raw) {
        return decode(raw, warning -> LOGGER.log(System.Logger.Level.WARNING, warning));
    }

    /**
     * Same as {@link #decode(String)}, but each warning — one per forbidden key found
     * and dropped, naming its JSON pointer (e.g. {@code /meta/attempts}) — is passed to
     * {@code onWarning} instead of the logger. Forbidden keys do not reject the message.
     */
    public static Envelope decode(String raw, Consumer<String> onWarning) {
        Objects.requireNonNull(onWarning, "onWarning");
        Object parsed;
        try {
            parsed = Json.parse(raw);
        } catch (RuntimeException ex) {
            return empty();
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return empty();
        }

        // job wins unless it is blank (closed ASCII set); then the urn alias is the identity. The
        // urn key itself is never retained; it only warns when it is dropped in favour of job.
        // A non-string job (null, number, boolean, object, array) is ignored with a warning and
        // never retained (ADR-0032 section 6); a blank string job falls back silently.
        Object rawJob = map.get("job");
        String job = asString(rawJob);
        if (map.containsKey("job") && !(rawJob instanceof String)) {
            boolean aliasUsable = !isBlankAscii(asString(map.get("urn")));
            onWarning.accept("BabelQueue: ignored /job because it is not a string"
                + (aliasUsable ? "; falling back to the urn alias" : ""));
        }
        if (map.containsKey("urn")) {
            String alias = asString(map.get("urn"));
            if (isBlankAscii(job) && alias != null) {
                job = alias;
            } else {
                onWarning.accept(forbiddenWarning("/urn"));
            }
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if ("urn".equals(key)) {
                continue;
            }
            if (FORBIDDEN_TOP_LEVEL.contains(key)) {
                onWarning.accept(forbiddenWarning("/" + key));
            } else if (!KNOWN_TOP_LEVEL.contains(key)) {
                extras.put(key, entry.getValue());
            }
        }

        return new Envelope(
            job,
            asString(map.get("trace_id")),
            asMap(map.get("data")),
            parseMeta(map.get("meta"), onWarning),
            asInt(map.get("attempts"), 0),
            parseDeadLetter(map.get("dead_letter")),
            extras);
    }

    private static String forbiddenWarning(String pointer) {
        return "BabelQueue: dropped forbidden envelope key " + pointer
            + " (message-envelope.md §10); it will not be re-emitted";
    }

    /** The message URN — the canonical {@code job}, with the {@code urn} alias resolved by {@link #decode}. */
    public static String urn(Envelope envelope) {
        return envelope.job() == null ? "" : stripAscii(envelope.job());
    }

    /**
     * Whether {@code value} is {@code null}, empty or only the closed ASCII whitespace set
     * (space, {@code \t}, {@code \n}, {@code \v}, {@code \f}, {@code \r}) — the suite's "blank"
     * (ADR-0031 (c)). Deliberately not {@link String#isBlank()}: Unicode spaces such as U+2003 are
     * not blank, for cross-SDK parity (GR-5).
     *
     * @param value the string to test, or {@code null}
     * @return {@code true} when {@code value} is {@code null}, empty or ASCII-whitespace only
     */
    public static boolean isBlankAscii(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isAsciiSpace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Trim only the closed ASCII whitespace set from both ends (see {@link #isBlankAscii}). */
    static String stripAscii(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isAsciiSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isAsciiSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isAsciiSpace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\n' || ch == 0x0B || ch == '\f' || ch == '\r';
    }

    /**
     * Whether a consumer should accept this envelope: rejects a missing URN, an
     * unsupported {@code meta.schema_version}, missing {@code data} or a blank
     * {@code trace_id} — the consumer-side counterpart to the producer JSON Schema.
     */
    public static boolean accepts(Envelope envelope) {
        if (urn(envelope).isEmpty()) {
            return false;
        }
        if (envelope.meta() == null || envelope.meta().schemaVersion() != SCHEMA_VERSION) {
            return false;
        }
        if (envelope.data() == null) {
            return false;
        }
        return !isBlankAscii(envelope.traceId());
    }

    private static Envelope empty() {
        return new Envelope(null, null, null, null, 0, null);
    }

    private static Meta parseMeta(Object value, Consumer<String> onWarning) {
        Map<String, Object> map = asMap(value);
        if (map == null) {
            return null;
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (FORBIDDEN_META.contains(key)) {
                onWarning.accept(forbiddenWarning("/meta/" + key));
            } else if (!KNOWN_META.contains(key)) {
                extras.put(key, entry.getValue());
            }
        }
        return new Meta(
            asString(map.get("id")),
            asString(map.get("queue")),
            asString(map.get("lang")),
            asInt(map.get("schema_version"), 0),
            asLong(map.get("created_at"), 0L),
            extras);
    }

    private static DeadLetter parseDeadLetter(Object value) {
        Map<String, Object> map = asMap(value);
        if (map == null) {
            return null;
        }
        return new DeadLetter(
            asString(map.get("reason")),
            asString(map.get("error")),
            asString(map.get("exception")),
            asLong(map.get("failed_at"), 0L),
            asString(map.get("original_queue")),
            asInt(map.get("attempts"), 0),
            asString(map.get("lang")));
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static int asInt(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static long asLong(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }
}
