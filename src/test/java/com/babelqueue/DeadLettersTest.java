package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DeadLettersTest {

    @Test
    void annotateAttachesBlockWithoutMutatingTheOriginal() {
        Envelope env =
            EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1L), "orders", null);

        Envelope dl = DeadLetters.annotate(env, "failed", "orders", 3, "boom", "java.lang.RuntimeException");

        assertNull(env.deadLetter(), "original must not be mutated");
        assertEquals("failed", dl.deadLetter().reason());
        assertEquals("orders", dl.deadLetter().originalQueue());
        assertEquals(3, dl.deadLetter().attempts());
        assertEquals("boom", dl.deadLetter().error());
        assertEquals("java.lang.RuntimeException", dl.deadLetter().exception());
        assertEquals(EnvelopeCodec.SOURCE_LANG, dl.deadLetter().lang());
        assertTrue(dl.deadLetter().failedAt() > 0);
    }

    @Test
    void annotateDefaultsErrorToNullAndAttemptsToEnvelope() {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1L));
        Envelope dl = DeadLetters.annotate(env, "expired", "orders");

        assertNull(dl.deadLetter().error());
        assertNull(dl.deadLetter().exception());
        assertEquals(0, dl.deadLetter().attempts());
    }

    @Test
    void deadLetterIsEncodedAsTheLastTopLevelField() {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1L));
        String body = EnvelopeCodec.encode(DeadLetters.annotate(env, "failed", "orders"));

        assertTrue(body.contains("\"dead_letter\":"));
        assertTrue(body.indexOf("\"dead_letter\"") > body.indexOf("\"attempts\""));
    }
}
