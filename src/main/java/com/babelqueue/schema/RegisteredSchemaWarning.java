package com.babelqueue.schema;

/**
 * A {@link SchemaWarning} together with the URN whose registered schema produced it, so an
 * operator can tell which schema asks for more than the validator enforces.
 *
 * @param urn     the message URN the schema is registered under
 * @param warning the unsupported-keyword warning ({@code pointer} and {@code keyword})
 */
public record RegisteredSchemaWarning(String urn, SchemaWarning warning) {

    /**
     * A human-readable line for logs; the conformance suite compares only the warning's
     * {@code pointer} and {@code keyword}.
     *
     * @return the message
     */
    public String message() {
        return "BabelQueue: schema for '" + urn + "': keyword '" + warning.keyword() + "' at '"
            + (warning.pointer().isEmpty() ? "<root>" : warning.pointer())
            + "' is not supported by the payload validator and is ignored";
    }
}
