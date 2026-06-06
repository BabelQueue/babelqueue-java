package com.babelqueue;

/**
 * Optionally implemented alongside {@link PolyglotMessage} to continue an existing
 * distributed trace instead of minting a fresh one.
 */
public interface HasTraceId {

    /** The trace id to reuse, or {@code null} to mint a new one. */
    String getBabelTraceId();
}
