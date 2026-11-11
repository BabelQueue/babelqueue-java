package com.babelqueue.schema;

/**
 * A registration-time warning that a schema uses a keyword outside the supported Draft-07
 * subset (ADR-0024, K-17). The keyword is ignored when validating, so the verdict is that of a
 * schema without it; the warning only tells the operator the schema asks for more than this
 * SDK enforces.
 *
 * @param pointer the RFC 6901 JSON Pointer, within the schema document, of the schema object
 *                that carries the keyword ({@code ""} is the root)
 * @param keyword the unsupported keyword
 */
public record SchemaWarning(String pointer, String keyword) {
}
