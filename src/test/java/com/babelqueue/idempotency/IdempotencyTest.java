package com.babelqueue.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelqueue.Envelope;
import com.babelqueue.Meta;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotencyTest {

    private Envelope msg(String id) {
        return new Envelope(
            "urn:babel:orders:created",
            "trace-1",
            Map.of("order_id", 7L),
            new Meta(id, "orders", "java", 1, 1L),
            0,
            null);
    }

    @Test
    void runsAndRemembersOnFirstDelivery() throws Exception {
        Store store = new InMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        Handler handler = Idempotent.wrap(store, env -> calls.incrementAndGet());

        handler.handle(msg("m1"));

        assertEquals(1, calls.get());
        assertTrue(store.seen("m1"));
    }

    @Test
    void skipsRedeliveryOfSameId() throws Exception {
        Store store = new InMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        Handler handler = Idempotent.wrap(store, env -> calls.incrementAndGet());

        handler.handle(msg("m1"));
        handler.handle(msg("m1")); // redelivery → skipped

        assertEquals(1, calls.get());
    }

    @Test
    void runsAgainForADifferentId() throws Exception {
        Store store = new InMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        Handler handler = Idempotent.wrap(store, env -> calls.incrementAndGet());

        handler.handle(msg("m1"));
        handler.handle(msg("m2"));

        assertEquals(2, calls.get());
    }

    @Test
    void doesNotRememberWhenHandlerThrows() {
        Store store = new InMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        Handler handler = Idempotent.wrap(store, env -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, () -> handler.handle(msg("m1")));
        assertFalse(store.seen("m1"));

        // A redelivery runs the handler again — retry works.
        assertThrows(IllegalStateException.class, () -> handler.handle(msg("m1")));
        assertEquals(2, calls.get());
    }

    @Test
    void runsWhenNoUsableId() throws Exception {
        Store store = new InMemoryStore();
        AtomicInteger calls = new AtomicInteger();
        Handler handler = Idempotent.wrap(store, env -> calls.incrementAndGet());

        handler.handle(msg("")); // empty id → cannot dedupe → runs
        handler.handle(msg(null)); // no id at all → runs

        assertEquals(2, calls.get());
    }

    @Test
    void forgetRemovesARememberedId() {
        InMemoryStore store = new InMemoryStore();
        store.remember("m1");
        assertTrue(store.seen("m1"));

        store.forget("m1");
        assertFalse(store.seen("m1"));
    }
}
