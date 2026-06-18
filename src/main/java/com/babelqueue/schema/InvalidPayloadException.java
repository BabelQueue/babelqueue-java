package com.babelqueue.schema;

import com.babelqueue.BabelQueueException;

/**
 * Raised when a message's {@code data} does not match the JSON Schema registered for its URN
 * (ADR-0024). The consumer-side {@link SchemaValidation#wrap} throws it so the adapter
 * redelivers (and eventually dead-letters) a poison message; the recommended primary use is
 * producer-side ({@link SchemaValidation#validate}) so invalid data never enters the queue.
 */
public class InvalidPayloadException extends BabelQueueException {

    private final transient String urn;
    private final transient String violation;

    /**
     * @param urn       the message URN whose schema was violated
     * @param violation the first {@code "<json-pointer>: <reason>"} mismatch
     */
    public InvalidPayloadException(String urn, String violation) {
        super("Message data for \"" + urn + "\" does not match its URN schema: " + violation + ".");
        this.urn = urn;
        this.violation = violation;
    }

    /** @return the message URN whose schema was violated */
    public String urn() {
        return urn;
    }

    /** @return the first {@code "<json-pointer>: <reason>"} mismatch */
    public String violation() {
        return violation;
    }
}
