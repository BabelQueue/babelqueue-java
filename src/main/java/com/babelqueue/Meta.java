package com.babelqueue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The immutable per-message metadata block of a {@link Envelope}.
 *
 * <p>{@code meta} keys this SDK does not know are kept in {@link #extras()} (insertion
 * order preserved) and re-emitted after the known keys, so consumers stay
 * forward-compatible (message-envelope.md §4: consumers MUST ignore — and a re-emitting
 * hop MUST NOT drop — unknown {@code meta} keys). Forbidden keys (§10) are never kept.
 *
 * @param id            a unique identifier for this specific message
 * @param queue         the logical queue the message was produced for
 * @param lang          the source SDK language (e.g. {@code "java"})
 * @param schemaVersion the wire envelope schema version
 * @param createdAt     creation time in Unix milliseconds, UTC
 * @param extras        unknown {@code meta} keys, insertion-ordered and unmodifiable (never {@code null})
 */
public record Meta(
    String id,
    String queue,
    String lang,
    int schemaVersion,
    long createdAt,
    Map<String, Object> extras
) {

    /** Normalises {@code extras} to an unmodifiable, insertion-ordered copy ({@code null} → empty). */
    public Meta {
        extras = extras == null || extras.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /** A meta block with no unknown keys (the pre-1.8.0 shape). */
    public Meta(String id, String queue, String lang, int schemaVersion, long createdAt) {
        this(id, queue, lang, schemaVersion, createdAt, null);
    }
}
