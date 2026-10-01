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

    /** Forbidden top-level keys (message-envelope.md §10) — never captured, never encoded. */
    private static final Set<String> FORBIDDEN_TOP_LEVEL = Set.of("timestamp");

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
        String resolvedUrn = urn == null ? "" : urn.strip();
        if (resolvedUrn.isEmpty()) {
            throw new BabelQueueException(
                "A polyglot message must expose a stable, non-empty URN so consumers "
                    + "can identify it without any class name.");
        }

        String trace = traceId == null ? "" : traceId.strip();
        if (trace.isEmpty()) {
            trace = UUID.randomUUID().toString();
        }

        Map<String, Object> payload =
            data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data);

        Meta meta = new Meta(
            UUID.randomUUID().toString(),
            queue == null ? "default" : queue,
            SOURCE_LANG,
            SCHEMA_VERSION,
            System.currentTimeMillis());

        return new Envelope(resolvedUrn, trace, payload, meta, 0, null);
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
     * Forbidden keys (§10) are never written, even if a caller put them into extras.
     */
    public static String encode(Envelope envelope) {
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
            appendExtras(metaMap, meta.extras(), KNOWN_META, FORBIDDEN_META);
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

        appendExtras(root, envelope.extras(), KNOWN_TOP_LEVEL, FORBIDDEN_TOP_LEVEL);
        return Json.write(root);
    }

    private static void appendExtras(
        Map<String, Object> target, Map<String, Object> extras, Set<String> known, Set<String> forbidden) {
        if (extras == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : extras.entrySet()) {
            String key = entry.getKey();
            if (key == null || known.contains(key) || forbidden.contains(key) || target.containsKey(key)) {
                continue;
            }
            target.put(key, entry.getValue());
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

        String job = asString(map.get("job"));
        if (job == null || job.isBlank()) {
            String alias = asString(map.get("urn"));
            if (alias != null) {
                job = alias;
            }
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
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
        return envelope.job() == null ? "" : envelope.job().strip();
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
        return envelope.traceId() != null && !envelope.traceId().isBlank();
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
