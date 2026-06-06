package com.babelqueue;

/**
 * What a consumer does with a message whose URN has no registered handler. The
 * string values are the canonical wire identifiers, shared with every other SDK.
 */
public final class UnknownUrnStrategy {

    /** Surface an error; let the worker decide. */
    public static final String FAIL = "fail";

    /** Drop the message. */
    public static final String DELETE = "delete";

    /** Requeue for another consumer. */
    public static final String RELEASE = "release";

    /** Route to the dead-letter queue. */
    public static final String DEAD_LETTER = "dead_letter";

    private UnknownUrnStrategy() {}
}
