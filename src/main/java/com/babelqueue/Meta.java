package com.babelqueue;

/**
 * The immutable per-message metadata block of a {@link Envelope}.
 *
 * @param id            a unique identifier for this specific message
 * @param queue         the logical queue the message was produced for
 * @param lang          the source SDK language (e.g. {@code "java"})
 * @param schemaVersion the wire envelope schema version
 * @param createdAt     creation time in Unix milliseconds, UTC
 */
public record Meta(
    String id,
    String queue,
    String lang,
    int schemaVersion,
    long createdAt
) {}
