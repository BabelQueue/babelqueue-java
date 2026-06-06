package com.babelqueue;

/** Raised when no handler is mapped for a message URN. */
public class UnknownUrnException extends BabelQueueException {

    public UnknownUrnException(String urn) {
        super("No handler is mapped for the message URN \"" + urn + "\".");
    }
}
