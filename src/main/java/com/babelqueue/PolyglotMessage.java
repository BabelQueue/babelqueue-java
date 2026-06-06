package com.babelqueue;

import java.util.Map;

/**
 * A message that can be produced as a polyglot envelope. Implement it on your own
 * classes so {@link EnvelopeCodec#fromMessage} can build the canonical envelope
 * without ever leaking a language-specific class name onto the wire.
 */
public interface PolyglotMessage {

    /** The stable URN that identifies this message across languages. */
    String getBabelUrn();

    /** The pure-JSON payload (no class instances). */
    Map<String, Object> toPayload();
}
