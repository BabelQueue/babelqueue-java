package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Redrive hands the reserved DLQ message to a {@link Redrive.KeyedRepublisher} for every re-publish. */
class RedriveKeyTest {

    /** Stands in for a broker-native delivery handle that carries a record key. */
    private record KeyHandle(byte[] key) {
    }

    private record Write(String queue, String body, byte[] key) {
    }

    /** A keyed transport: pop hands out messages whose handle carries a key; publishFrom records it. */
    private static final class KeyedTransport implements Redrive.Transport, Redrive.KeyedRepublisher {
        final Map<String, Deque<Redrive.Reserved>> pending = new HashMap<>();
        final List<Write> writes = new ArrayList<>();
        final List<String> plain = new ArrayList<>();
        final String failQueue;

        KeyedTransport(String failQueue) {
            this.failQueue = failQueue;
        }

        void put(String queue, String body, byte[] key) {
            pending.computeIfAbsent(queue, k -> new ArrayDeque<>()).addLast(new Redrive.Reserved(body, new KeyHandle(key)));
        }

        @Override
        public Redrive.Reserved pop(String queue) {
            Deque<Redrive.Reserved> dq = pending.get(queue);
            return dq == null ? null : dq.pollFirst();
        }

        @Override
        public void publish(String queue, String body) {
            plain.add(queue);
        }

        @Override
        public void ack(Redrive.Reserved message) {
        }

        @Override
        public void publishFrom(String queue, String body, Redrive.Reserved from) {
            if (queue.equals(failQueue)) {
                throw new IllegalStateException("publish refused");
            }
            writes.add(new Write(queue, body, ((KeyHandle) from.handle()).key()));
        }
    }

    private static String dlqBody() {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1), "orders", null);
        return EnvelopeCodec.encode(DeadLetters.annotate(env, "failed", "orders"));
    }

    private static void assertKey(byte[] expected, byte[] actual) {
        if (expected == null) {
            assertNull(actual, "a null key must stay null");
        } else {
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    void redriveCarriesTheRecordKeyByteForByte() throws Exception {
        for (byte[] key : new byte[][] {"order-1042".getBytes(), null, new byte[0]}) {
            KeyedTransport t = new KeyedTransport(null);
            t.put("orders.dlq", dlqBody(), key);
            Redrive.Result res = Redrive.redrive(t, "orders.dlq", Redrive.Options.all());
            assertEquals(1, res.redriven());
            assertEquals(1, t.writes.size());
            assertEquals("orders", t.writes.get(0).queue());
            assertKey(key, t.writes.get(0).key());
            assertTrue(t.plain.isEmpty(), "plain publish must not be used");
        }
    }

    @Test
    void restorePathsCarryTheRecordKeyBackToTheDlq() throws Exception {
        byte[] key = "order-1042".getBytes();

        KeyedTransport dry = new KeyedTransport(null);
        dry.put("orders.dlq", dlqBody(), key);
        Redrive.redrive(dry, "orders.dlq", Redrive.Options.all().dryRun(true));
        assertEquals(1, dry.writes.size());
        assertEquals("orders.dlq", dry.writes.get(0).queue());
        assertKey(key, dry.writes.get(0).key());

        KeyedTransport unselected = new KeyedTransport(null);
        unselected.put("orders.dlq", dlqBody(), key);
        Redrive.redrive(unselected, "orders.dlq", Redrive.Options.all().select(e -> false));
        assertEquals("orders.dlq", unselected.writes.get(0).queue());
        assertKey(key, unselected.writes.get(0).key());

        KeyedTransport poison = new KeyedTransport(null);
        poison.put("orders.dlq", "not json", key);
        Redrive.redrive(poison, "orders.dlq", Redrive.Options.all());
        assertEquals("orders.dlq", poison.writes.get(0).queue());
        assertKey(key, poison.writes.get(0).key());

        KeyedTransport failing = new KeyedTransport("orders");
        failing.put("orders.dlq", dlqBody(), key);
        assertThrows(IllegalStateException.class, () -> Redrive.redrive(failing, "orders.dlq", Redrive.Options.all()));
        assertEquals(1, failing.writes.size());
        assertEquals("orders.dlq", failing.writes.get(0).queue());
        assertKey(key, failing.writes.get(0).key());
    }
}
