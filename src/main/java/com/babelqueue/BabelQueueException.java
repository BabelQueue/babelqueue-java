package com.babelqueue;

/** Base unchecked exception for all BabelQueue failures. */
public class BabelQueueException extends RuntimeException {

    public BabelQueueException(String message) {
        super(message);
    }

    public BabelQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
