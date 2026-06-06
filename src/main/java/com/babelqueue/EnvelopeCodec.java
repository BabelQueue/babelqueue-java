package com.babelqueue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
     * unescaped, matching the other SDK cores; the field order is canonical.
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

        return Json.write(root);
    }

    /**
     * Parse a raw JSON body into an {@link Envelope}. Malformed or non-object input
     * yields an empty envelope (so {@link #accepts} returns {@code false}); the
     * {@code urn} inbound alias is resolved into {@code job}. Does not validate the
     * contents — call {@link #accepts} first.
     */
    public static Envelope decode(String raw) {
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

        return new Envelope(
            job,
            asString(map.get("trace_id")),
            asMap(map.get("data")),
            parseMeta(map.get("meta")),
            asInt(map.get("attempts"), 0),
            parseDeadLetter(map.get("dead_letter")));
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

    private static Meta parseMeta(Object value) {
        Map<String, Object> map = asMap(value);
        if (map == null) {
            return null;
        }
        return new Meta(
            asString(map.get("id")),
            asString(map.get("queue")),
            asString(map.get("lang")),
            asInt(map.get("schema_version"), 0),
            asLong(map.get("created_at"), 0L));
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
