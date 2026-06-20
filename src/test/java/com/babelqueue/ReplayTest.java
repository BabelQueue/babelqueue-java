package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelqueue.idempotency.Handler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplayTest {

    /** In-memory transport that carries out-of-band headers (a {@link Redrive.HeaderPublisher}). */
    private static final class MemoryTransport implements Redrive.Transport, Redrive.HeaderPublisher {
        private final Map<String, Deque<Redrive.Reserved>> queues = new HashMap<>();

        @Override
        public Redrive.Reserved pop(String queue) {
            Deque<Redrive.Reserved> dq = queues.get(queue);
            return (dq == null || dq.isEmpty()) ? null : dq.pollFirst();
        }

        @Override
        public void publish(String queue, String body) {
            queues.computeIfAbsent(queue, k -> new ArrayDeque<>()).addLast(new Redrive.Reserved(body, null));
        }

        @Override
        public void publishWithHeaders(String queue, String body, Map<String, String> headers) {
            queues.computeIfAbsent(queue, k -> new ArrayDeque<>()).addLast(new Redrive.Reserved(body, null, headers));
        }

        @Override
        public void ack(Redrive.Reserved message) {
            // pop already removed it
        }
    }

    /** In-memory transport with no header capability (NOT a {@link Redrive.HeaderPublisher}). */
    private static final class PlainTransport implements Redrive.Transport {
        private final Map<String, Deque<String>> queues = new HashMap<>();

        @Override
        public Redrive.Reserved pop(String queue) {
            Deque<String> dq = queues.get(queue);
            return (dq == null || dq.isEmpty()) ? null : new Redrive.Reserved(dq.pollFirst(), null);
        }

        @Override
        public void publish(String queue, String body) {
            queues.computeIfAbsent(queue, k -> new ArrayDeque<>()).addLast(body);
        }

        @Override
        public void ack(Redrive.Reserved message) {
            // pop already removed it
        }
    }

    private static void deadLettered(Redrive.Transport t, String dlq, String urn, String originalQueue)
        throws Exception {
        Envelope env = EnvelopeCodec.make(urn, Map.of("order_id", 1), originalQueue, null);
        Envelope dl = DeadLetters.annotate(env, "failed", originalQueue);
        t.publish(dlq, EnvelopeCodec.encode(dl));
    }

    @Test
    void isReplayDefaultsFalseAndBypassRunsEffect() throws Exception {
        assertFalse(Replay.isReplay());
        boolean[] ran = {false};
        Replay.bypassExternalEffects(() -> ran[0] = true);
        assertTrue(ran[0], "effect must run when not a replay");
    }

    @Test
    void processEstablishesReplayScopeAndBypassSkips() throws Exception {
        boolean[] ran = {false};
        boolean[] sawReplay = {false};
        Replay.process(Map.of(Replay.HEADER_REPLAY_BYPASS, "1"), () -> {
            sawReplay[0] = Replay.isReplay();
            Replay.bypassExternalEffects(() -> ran[0] = true);
        });
        assertTrue(sawReplay[0], "isReplay must be true inside the process scope");
        assertFalse(ran[0], "the effect must be skipped on a replay");
        assertFalse(Replay.isReplay(), "the flag must be restored after process");
    }

    @Test
    void processWithoutHeaderIsNotReplay() throws Exception {
        boolean[] ran = {false};
        Replay.process(Map.of(), () -> Replay.bypassExternalEffects(() -> ran[0] = true));
        assertTrue(ran[0], "a message with no replay header is not a replay");
    }

    @Test
    void redriveBypassStampsHeaderAndConsumeSkipsEffect() throws Exception {
        MemoryTransport t = new MemoryTransport();
        deadLettered(t, "orders.dlq", "urn:babel:orders:created", "orders");

        Redrive.Result res = Redrive.redrive(t, "orders.dlq", Redrive.Options.all().bypass(true));
        assertEquals(1, res.redriven());
        assertTrue(res.items().get(0).bypassed(), "the item must be flagged bypassed");

        Redrive.Reserved msg = t.pop("orders");
        assertNotNull(msg);
        assertEquals("1", msg.headers().get(Replay.HEADER_REPLAY_BYPASS), "redriven message carries the header");

        Envelope env = EnvelopeCodec.decode(msg.body());
        boolean[] emailed = {false};
        Handler handler = e -> {
            assertTrue(Replay.isReplay(), "the handler should see this as a replay");
            Replay.bypassExternalEffects(() -> emailed[0] = true);
        };
        Replay.process(msg.headers(), () -> handler.handle(env));
        assertFalse(emailed[0], "the external side-effect must be skipped on a bypassed replay");
    }

    @Test
    void bypassWithoutHeaderPublisherFallsBack() throws Exception {
        PlainTransport t = new PlainTransport();
        deadLettered(t, "dlq", "urn:babel:orders:created", "orders");

        Redrive.Result res = Redrive.redrive(t, "dlq", Redrive.Options.all().bypass(true));
        assertEquals(1, res.redriven());
        assertFalse(res.items().get(0).bypassed(), "bypass must be a no-op without a HeaderPublisher");
    }
}
