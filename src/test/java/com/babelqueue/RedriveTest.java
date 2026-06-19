package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedriveTest {

    /** In-memory transport for tests; optionally refuses to publish to one queue. */
    private static final class MemoryTransport implements Redrive.Transport {
        private final Map<String, Deque<String>> queues = new HashMap<>();
        private final String failQueue;

        MemoryTransport() {
            this(null);
        }

        MemoryTransport(String failQueue) {
            this.failQueue = failQueue;
        }

        @Override
        public Redrive.Reserved pop(String queue) {
            Deque<String> dq = queues.get(queue);
            if (dq == null || dq.isEmpty()) {
                return null;
            }
            return new Redrive.Reserved(dq.pollFirst(), null);
        }

        @Override
        public void publish(String queue, String body) {
            if (queue.equals(failQueue)) {
                throw new IllegalStateException("publish refused");
            }
            queues.computeIfAbsent(queue, k -> new ArrayDeque<>()).addLast(body);
        }

        @Override
        public void ack(Redrive.Reserved message) {
            // pop already removed it
        }

        int size(String queue) {
            Deque<String> dq = queues.get(queue);
            return dq == null ? 0 : dq.size();
        }
    }

    private static Envelope deadLettered(MemoryTransport t, String dlq, String urn, String originalQueue) {
        Envelope env = EnvelopeCodec.make(urn, Map.of("order_id", 1), originalQueue, null);
        Envelope dl = DeadLetters.annotate(env, "failed", originalQueue);
        t.publish(dlq, EnvelopeCodec.encode(dl));
        return dl;
    }

    private static List<Envelope> drain(MemoryTransport t, String queue) {
        List<Envelope> out = new ArrayList<>();
        Redrive.Reserved m;
        while ((m = t.pop(queue)) != null) {
            out.add(EnvelopeCodec.decode(m.body()));
        }
        return out;
    }

    @Test
    void redrivesToSourceAndResets() throws Exception {
        MemoryTransport t = new MemoryTransport();
        Envelope orig = deadLettered(t, "orders.dlq", "urn:babel:orders:created", "orders");

        Redrive.Result res = Redrive.redrive(t, "orders.dlq", Redrive.Options.all());

        assertEquals(1, res.redriven());
        assertEquals(0, res.skipped());
        List<Envelope> got = drain(t, "orders");
        assertEquals(1, got.size());
        assertNull(got.get(0).deadLetter(), "dead_letter must be stripped");
        assertEquals(0, got.get(0).attempts(), "attempts must reset");
        assertEquals(orig.traceId(), got.get(0).traceId(), "trace_id must be preserved");
        assertEquals("urn:babel:orders:created", got.get(0).job());
        assertEquals(0, t.size("orders.dlq"));
    }

    @Test
    void redrivesToSandbox() throws Exception {
        MemoryTransport t = new MemoryTransport();
        deadLettered(t, "orders.dlq", "urn:babel:orders:created", "orders");

        Redrive.Result res = Redrive.redrive(t, "orders.dlq", Redrive.Options.all().toQueue("sandbox"));

        assertEquals(1, res.redriven());
        assertEquals(0, t.size("orders"));
        assertEquals(1, t.size("sandbox"));
    }

    @Test
    void dryRunReportsPlanAndChangesNothing() throws Exception {
        MemoryTransport t = new MemoryTransport();
        deadLettered(t, "orders.dlq", "urn:babel:orders:created", "orders");

        Redrive.Result res = Redrive.redrive(t, "orders.dlq", Redrive.Options.all().dryRun(true));

        assertEquals(0, res.redriven());
        assertEquals(1, res.skipped());
        assertEquals("orders", res.items().get(0).to());
        assertFalse(res.items().get(0).redriven());
        assertEquals(0, t.size("orders"));
        assertEquals(1, t.size("orders.dlq"));
        assertNotNull(drain(t, "orders.dlq").get(0).deadLetter(), "DLQ message left unchanged");
    }

    @Test
    void selectRedrivesOnlyMatching() throws Exception {
        MemoryTransport t = new MemoryTransport();
        deadLettered(t, "dlq", "urn:babel:orders:created", "orders");
        deadLettered(t, "dlq", "urn:babel:emails:welcome", "emails");

        Redrive.Result res = Redrive.redrive(t, "dlq",
            Redrive.Options.all().select(e -> "urn:babel:orders:created".equals(e.job())));

        assertEquals(1, res.redriven());
        assertEquals(1, res.skipped());
        assertEquals(1, t.size("orders"));
        assertEquals(0, t.size("emails"));
        assertEquals(1, t.size("dlq"), "unselected message is restored to the DLQ");
    }

    @Test
    void maxCapsHowManyArePulled() throws Exception {
        MemoryTransport t = new MemoryTransport();
        for (int i = 0; i < 3; i++) {
            deadLettered(t, "dlq", "urn:babel:orders:created", "orders");
        }

        Redrive.Result res = Redrive.redrive(t, "dlq", Redrive.Options.all().max(2));

        assertEquals(2, res.redriven());
        assertEquals(1, t.size("dlq"));
    }

    @Test
    void publishFailureRestoresToDlq() {
        MemoryTransport t = new MemoryTransport("orders");
        deadLettered(t, "dlq", "urn:babel:orders:created", "orders");

        assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "dlq", Redrive.Options.all()));

        assertEquals(1, t.size("dlq"), "a message must be restored when its re-publish fails");
        assertEquals(0, t.size("orders"));
    }

    @Test
    void undecodableBodyIsRestored() throws Exception {
        MemoryTransport t = new MemoryTransport();
        t.publish("dlq", "not-json{{{");

        Redrive.Result res = Redrive.redrive(t, "dlq", Redrive.Options.all());

        assertEquals(0, res.redriven());
        assertEquals(1, res.skipped());
        assertEquals("not-json{{{", t.pop("dlq").body(), "undecodable body must be restored");
    }

    @Test
    void noDeadLetterFallsBackToMetaQueue() throws Exception {
        MemoryTransport t = new MemoryTransport();
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of(), "orders", null);
        t.publish("dlq", EnvelopeCodec.encode(env));

        Redrive.Result res = Redrive.redrive(t, "dlq", Redrive.Options.all());

        assertEquals(1, res.redriven());
        assertEquals(1, t.size("orders"));
    }
}
