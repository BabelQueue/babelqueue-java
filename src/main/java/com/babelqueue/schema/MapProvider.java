package com.babelqueue.schema;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link SchemaProvider}, for tests and for embedding schemas in code. Construct it
 * with each URN's already-decoded JSON Schema (a {@code Map}).
 */
public final class MapProvider implements SchemaProvider {

    private final Map<String, Map<String, Object>> schemas;

    /**
     * @param schemas urn -&gt; decoded JSON Schema
     */
    public MapProvider(Map<String, Map<String, Object>> schemas) {
        this.schemas = new HashMap<>(schemas);
    }

    @Override
    public Map<String, Object> schemaFor(String urn) {
        return schemas.get(urn);
    }
}
