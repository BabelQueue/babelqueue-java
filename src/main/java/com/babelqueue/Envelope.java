package com.babelqueue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical BabelQueue wire message: a strict, language-neutral JSON shape
 * ({@code {job, trace_id, data, meta, attempts}}) that every SDK produces and
 * consumes identically — no language-specific serialization on the wire.
 *
 * <p>Build one with {@link EnvelopeCodec#make}, render it with
 * {@link EnvelopeCodec#encode}, and parse inbound bytes with
 * {@link EnvelopeCodec#decode}. The record is immutable; {@link DeadLetters}
 * returns a copy with a {@link DeadLetter} attached.
 *
 * <p>Top-level keys this SDK does not know (a newer producer's additions) are kept in
 * {@link #extras()} — in their original order — so a decode → re-emit chain (retry, DLQ,
 * redrive) forwards them unchanged (message-envelope.md §8: consumers MUST tolerate unknown
 * keys). Forbidden keys (§10) are never captured there and never encoded.
 *
 * @param job        the message URN (never a class name)
 * @param traceId    correlation id, preserved across every hop
 * @param data       the pure-JSON payload
 * @param meta       the immutable metadata block
 * @param attempts   the top-level transport retry counter
 * @param deadLetter the dead-letter block, or {@code null} until dead-lettered
 * @param extras     unknown top-level keys, insertion-ordered and unmodifiable (never {@code null})
 */
public record Envelope(
    String job,
    String traceId,
    Map<String, Object> data,
    Meta meta,
    int attempts,
    DeadLetter deadLetter,
    Map<String, Object> extras
) {

    /** Normalises {@code extras} to an unmodifiable, insertion-ordered copy ({@code null} → empty). */
    public Envelope {
        extras = extras == null || extras.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /** An envelope with no unknown top-level keys (the pre-1.8.0 shape). */
    public Envelope(
        String job,
        String traceId,
        Map<String, Object> data,
        Meta meta,
        int attempts,
        DeadLetter deadLetter) {
        this(job, traceId, data, meta, attempts, deadLetter, null);
    }

    /**
     * A copy with {@code attempts} replaced — the retry/release path. Everything else,
     * including the {@code meta} and top-level {@link #extras()}, is carried over unchanged.
     */
    public Envelope withAttempts(int attempts) {
        return new Envelope(job, traceId, data, meta, attempts, deadLetter, extras);
    }

    /** A copy with the {@code dead_letter} block replaced ({@code null} removes it); extras are kept. */
    public Envelope withDeadLetter(DeadLetter deadLetter) {
        return new Envelope(job, traceId, data, meta, attempts, deadLetter, extras);
    }
}
