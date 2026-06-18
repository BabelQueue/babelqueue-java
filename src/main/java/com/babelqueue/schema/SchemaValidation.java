package com.babelqueue.schema;

import com.babelqueue.idempotency.Handler;
import java.util.Map;

/**
 * Optional per-URN {@code data} schema validation for a babelqueue producer or consumer
 * (ADR-0024). A {@link SchemaProvider} supplies a JSON Schema for a message URN — typically
 * built from a babelqueue-registry {@code registry.json} — and the message's {@code data} is
 * validated against it. It is opt-in: a URN with no registered schema is never validated.
 *
 * <ul>
 *   <li><b>Producer-side (recommended):</b> call {@link #validate} before publishing so
 *       invalid data never enters the queue, or {@link #check} to branch without throwing.</li>
 *   <li><b>Consumer-side (safety net):</b> wrap a handler with {@link #wrap}. Invalid data
 *       throws {@link InvalidPayloadException}, so the adapter redelivers (and eventually
 *       dead-letters) the poison message; a URN with no schema runs the handler unchanged.
 *       It reuses the shared {@link Handler} so it composes with {@code Idempotent.wrap}.</li>
 * </ul>
 *
 * <p>The Java mirror of the Go {@code schema.Check}/{@code schema.Wrap} helpers.
 */
public final class SchemaValidation {

    private SchemaValidation() {
    }

    /**
     * The first {@code data} violation for {@code (urn, data)}, or {@code null} when it is
     * valid or when no schema is registered for the URN (opt-in). For producer-side branching.
     *
     * @param provider the schema source
     * @param urn      the message URN
     * @param data     the message data
     * @return the first violation, or null
     */
    public static String check(SchemaProvider provider, String urn, Map<String, Object> data) {
        Map<String, Object> schema = provider.schemaFor(urn);
        if (schema == null) {
            return null;
        }
        return PayloadValidator.validate(schema, data);
    }

    /**
     * Validate {@code (urn, data)} against its registered schema, throwing otherwise. The
     * producer-side guard; call it before publishing.
     *
     * @param provider the schema source
     * @param urn      the message URN
     * @param data     the message data
     * @throws InvalidPayloadException when the data does not match the URN's schema
     */
    public static void validate(SchemaProvider provider, String urn, Map<String, Object> data) {
        String violation = check(provider, urn, data);
        if (violation != null) {
            throw new InvalidPayloadException(urn, violation);
        }
    }

    /**
     * Returns {@code handler} wrapped to validate each message's {@code data} against its
     * URN's schema before the handler runs (consumer-side safety net).
     *
     * @param provider the schema source
     * @param handler  the handler to guard
     * @return the wrapped handler
     */
    public static Handler wrap(SchemaProvider provider, Handler handler) {
        return envelope -> {
            validate(provider, envelope.job(), envelope.data());
            handler.handle(envelope);
        };
    }
}
