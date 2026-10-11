package com.babelqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A failed restore write is never acknowledged and, on a {@link Redrive.Rewinder}, the message is rewound. */
class RedriveRewindTest {

    /** Fails every write to {@code failQueue}; optionally also fails the rewind. */
    private static class FlakyTransport implements Redrive.Transport {
        final Deque<Redrive.Reserved> pending = new ArrayDeque<>();
        final List<String> writes = new ArrayList<>();
        final List<Redrive.Reserved> acked = new ArrayList<>();
        final String failQueue;

        FlakyTransport(String failQueue) {
            this.failQueue = failQueue;
        }

        @Override
        public Redrive.Reserved pop(String queue) {
            return pending.pollFirst();
        }

        @Override
        public void publish(String queue, String body) {
            if (queue.equals(failQueue)) {
                throw new IllegalStateException("write to " + queue + " refused");
            }
            writes.add(queue);
        }

        @Override
        public void ack(Redrive.Reserved message) {
            acked.add(message);
        }
    }

    private static class RewindingTransport extends FlakyTransport implements Redrive.Rewinder {
        final List<Redrive.Reserved> rewound = new ArrayList<>();
        RuntimeException rewindFailure;

        RewindingTransport(String failQueue) {
            super(failQueue);
        }

        @Override
        public void rewind(Redrive.Reserved message) {
            rewound.add(message);
            if (rewindFailure != null) {
                throw rewindFailure;
            }
        }
    }

    private static String dlqBody() {
        Envelope env = EnvelopeCodec.make("urn:babel:orders:created", Map.of("order_id", 1), "orders", null);
        return EnvelopeCodec.encode(DeadLetters.annotate(env, "failed", "orders"));
    }

    private static Redrive.Reserved reserved(String body) {
        return new Redrive.Reserved(body, new Object());
    }

    @Test
    void failedRestoreOfAnUndecodableBodyRewindsAndDoesNotAck() {
        RewindingTransport t = new RewindingTransport("orders.dlq");
        Redrive.Reserved msg = reserved("not an envelope");
        t.pending.add(msg);

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all()));

        assertEquals(List.of(msg), t.rewound);
        assertTrue(t.acked.isEmpty(), "a message whose restore failed must not be acknowledged");
        assertEquals(0, e.getSuppressed().length);
    }

    @Test
    void failedRestoreOfADryRunMessageRewindsAndDoesNotAck() {
        RewindingTransport t = new RewindingTransport("orders.dlq");
        Redrive.Reserved msg = reserved(dlqBody());
        t.pending.add(msg);

        assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all().dryRun(true)));

        assertEquals(List.of(msg), t.rewound);
        assertTrue(t.acked.isEmpty());
    }

    @Test
    void failedRestoreOfAnUnselectedMessageRewindsAndDoesNotAck() {
        RewindingTransport t = new RewindingTransport("orders.dlq");
        Redrive.Reserved msg = reserved(dlqBody());
        t.pending.add(msg);

        assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all().select(env -> false)));

        assertEquals(List.of(msg), t.rewound);
        assertTrue(t.acked.isEmpty());
    }

    @Test
    void failedRedriveWriteFollowedByFailedRestoreRewindsAndReportsBothErrors() {
        // Both the work-queue write and the restore write back to the DLQ are refused.
        RewindingTransport t = new RewindingTransport(null) {
            @Override
            public void publish(String queue, String body) {
                throw new IllegalStateException("write to " + queue + " refused");
            }
        };
        Redrive.Reserved msg = reserved(dlqBody());
        t.pending.add(msg);

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all()));

        assertEquals("write to orders.dlq refused", e.getMessage());
        assertEquals(1, e.getSuppressed().length, "the original redrive-write failure is attached");
        assertEquals("write to orders refused", e.getSuppressed()[0].getMessage());
        assertEquals(List.of(msg), t.rewound);
        assertTrue(t.acked.isEmpty());
    }

    @Test
    void failedRedriveWriteWithASuccessfulRestoreStillRewindsTheRestOfTheBatch() {
        // The work-queue write is refused, the restore to the DLQ works: the run stops after the first
        // message, so the second (reserved but never processed) must be handed back too.
        RewindingTransport t = new RewindingTransport("orders");
        Redrive.Reserved first = reserved(dlqBody());
        Redrive.Reserved second = reserved(dlqBody());
        t.pending.add(first);
        t.pending.add(second);

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all()));

        assertEquals("write to orders refused", e.getMessage());
        assertEquals(List.of(first), t.acked, "the restored message is acknowledged exactly once");
        assertEquals(List.of(first), t.rewound, "rewind is told which message is already acknowledged");
        assertEquals(0, e.getSuppressed().length);
    }

    @Test
    void failedRewindAfterASuccessfulRestoreIsReportedWithThePublishError() {
        RewindingTransport t = new RewindingTransport("orders");
        IllegalStateException rewindFailure = new IllegalStateException("seek failed");
        t.rewindFailure = rewindFailure;
        t.pending.add(reserved(dlqBody()));

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all()));

        assertEquals("write to orders refused", e.getMessage());
        assertEquals(1, e.getSuppressed().length);
        assertSame(rewindFailure, e.getSuppressed()[0]);
    }

    @Test
    void failedRewindIsReportedAlongsideTheRestoreError() {
        RewindingTransport t = new RewindingTransport("orders.dlq");
        IllegalStateException rewindFailure = new IllegalStateException("seek failed");
        t.rewindFailure = rewindFailure;
        t.pending.add(reserved("not an envelope"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all()));

        assertEquals("write to orders.dlq refused", e.getMessage());
        assertEquals(1, e.getSuppressed().length);
        assertSame(rewindFailure, e.getSuppressed()[0]);
        assertTrue(t.acked.isEmpty(), "the message is never acknowledged, even when the rewind fails too");
    }

    @Test
    void successfulRestoreAndRedriveNeverRewind() throws Exception {
        RewindingTransport t = new RewindingTransport(null);
        t.pending.add(reserved(dlqBody()));
        t.pending.add(reserved("not an envelope"));

        Redrive.Result result = Redrive.redrive(t, "orders.dlq", Redrive.Options.all());

        assertEquals(1, result.redriven());
        assertEquals(1, result.skipped());
        assertTrue(t.rewound.isEmpty());
        assertEquals(2, t.acked.size());
    }

    @Test
    void throwingSelectPredicateRewindsTheMessageAndTheRestOfTheBatch() {
        RewindingTransport t = new RewindingTransport(null);
        Redrive.Reserved first = reserved(dlqBody());
        Redrive.Reserved second = reserved(dlqBody());
        t.pending.add(first);
        t.pending.add(second);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all().select(env -> {
                if (calls.incrementAndGet() == 2) {
                    throw new IllegalArgumentException("select blew up");
                }
                return true;
            })));

        assertEquals("select blew up", e.getMessage());
        assertEquals(List.of(first), t.acked, "only the first message was redriven and acknowledged");
        assertEquals(List.of(second), t.rewound, "the message being processed is handed back, unacknowledged");
    }

    @Test
    void failedAckRewindsTheMessage() {
        RewindingTransport t = new RewindingTransport(null) {
            @Override
            public void ack(Redrive.Reserved message) {
                throw new IllegalStateException("commit timed out");
            }
        };
        Redrive.Reserved first = reserved(dlqBody());
        t.pending.add(first);

        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all()));

        assertEquals("commit timed out", e.getMessage());
        assertEquals(List.of(first), t.rewound);
    }

    @Test
    void transportWithoutRewinderKeepsLeavingTheMessageUnacknowledged() {
        FlakyTransport t = new FlakyTransport("orders.dlq");
        t.pending.add(reserved("not an envelope"));

        assertThrows(IllegalStateException.class,
            () -> Redrive.redrive(t, "orders.dlq", Redrive.Options.all()));

        assertTrue(t.acked.isEmpty());
    }
}
