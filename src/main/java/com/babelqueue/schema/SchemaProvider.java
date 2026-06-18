package com.babelqueue.schema;

import java.util.Map;

/**
 * A source of per-URN {@code data} schemas, keyed on the message URN (ADR-0024). Given a
 * URN, it returns the decoded JSON Schema for that message's {@code data} block, or
 * {@code null} when no schema is registered — in which case the caller skips validation
 * (the feature is opt-in).
 *
 * <p>The reference {@link MapProvider} is in-memory. A Java app reads its babelqueue-registry
 * {@code registry.json} with its own JSON tooling and passes the parsed schemas to a
 * {@link MapProvider}; the zero-dependency core does not ship a file-based provider. This is
 * the Java mirror of the Go {@code schema.Provider} interface.
 */
public interface SchemaProvider {

    /**
     * The decoded JSON Schema registered for {@code urn}, or {@code null} when none is.
     *
     * @param urn the message URN
     * @return the schema map, or null
     */
    Map<String, Object> schemaFor(String urn);
}
