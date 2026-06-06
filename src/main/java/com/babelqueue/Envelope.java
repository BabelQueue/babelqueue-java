package com.babelqueue;

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
 * @param job        the message URN (never a class name)
 * @param traceId    correlation id, preserved across every hop
 * @param data       the pure-JSON payload
 * @param meta       the immutable metadata block
 * @param attempts   the top-level transport retry counter
 * @param deadLetter the dead-letter block, or {@code null} until dead-lettered
 */
public record Envelope(
    String job,
    String traceId,
    Map<String, Object> data,
    Meta meta,
    int attempts,
    DeadLetter deadLetter
) {}
