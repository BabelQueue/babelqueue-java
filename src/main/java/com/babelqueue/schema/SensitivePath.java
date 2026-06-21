package com.babelqueue.schema;

/**
 * One property marked {@code x-gdpr-sensitive} (ADR-0030), located by its dotted path from the
 * schema root. Array element schemas use the {@code "field[]"} segment (matching the validator /
 * babelqueue-registry {@code compat} convention); a mark on the root schema itself is the empty
 * path {@code ""}. {@code category} is the optional {@code "x-gdpr-sensitive": "<category>"} string
 * (e.g. {@code "email"}), or {@code ""} when the keyword was the boolean {@code true}.
 *
 * <p>It is the value-level counterpart to babelqueue-registry's inventory: the
 * {@link com.babelqueue.gdpr.Gdpr} helpers use these paths to locate the leaves they must encrypt on
 * produce and decrypt on consume. The Java mirror of the Go {@code schema.SensitivePath}.
 *
 * @param path     the dotted path to the marked property ({@code ""} for a root mark)
 * @param category the optional category string, or {@code ""}
 */
public record SensitivePath(String path, String category) {
}
