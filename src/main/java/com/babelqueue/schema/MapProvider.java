package com.babelqueue.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * In-memory {@link SchemaProvider}, for tests and for embedding schemas in code. Construct it
 * with each URN's already-decoded JSON Schema (a {@code Map}).
 *
 * <p>Construction is where a schema is first processed: every keyword outside the supported
 * subset (see {@link PayloadValidator#warnings}) produces one {@link RegisteredSchemaWarning}
 * (the {@link SchemaWarning} plus the URN it was found under), handed to the hook
 * ({@link System.Logger} at {@code WARNING} by default, like the envelope codec) and
 * kept in {@link #warnings()}. Warnings never change a validation verdict.
 */
public final class MapProvider implements SchemaProvider {

    private static final System.Logger LOGGER = System.getLogger(MapProvider.class.getName());

    private final Map<String, Map<String, Object>> schemas;
    private final List<RegisteredSchemaWarning> warnings;

    /**
     * Warnings go to {@link System.Logger} at {@code WARNING}.
     *
     * @param schemas urn -&gt; decoded JSON Schema
     */
    public MapProvider(Map<String, Map<String, Object>> schemas) {
        this(schemas, warning -> LOGGER.log(System.Logger.Level.WARNING, warning.message()));
    }

    /**
     * @param schemas   urn -&gt; decoded JSON Schema
     * @param onWarning receives each registration warning (with its URN) instead of the logger
     */
    public MapProvider(
        Map<String, Map<String, Object>> schemas, Consumer<RegisteredSchemaWarning> onWarning) {
        Objects.requireNonNull(onWarning, "onWarning");
        this.schemas = new HashMap<>(schemas);
        List<RegisteredSchemaWarning> collected = new ArrayList<>();
        // Iterate the supplied map, not the copy: the order is that of the caller's map.
        for (Map.Entry<String, Map<String, Object>> entry : schemas.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            for (SchemaWarning warning : PayloadValidator.warnings(entry.getValue())) {
                RegisteredSchemaWarning registered = new RegisteredSchemaWarning(entry.getKey(), warning);
                collected.add(registered);
                onWarning.accept(registered);
            }
        }
        this.warnings = Collections.unmodifiableList(collected);
    }

    /**
     * The warnings produced when the schemas were registered, in the iteration order of the
     * map passed to the constructor (document order within one schema).
     *
     * @return an unmodifiable list, empty when every keyword is supported
     */
    public List<RegisteredSchemaWarning> warnings() {
        return warnings;
    }

    @Override
    public Map<String, Object> schemaFor(String urn) {
        return schemas.get(urn);
    }
}
