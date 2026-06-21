package com.babelqueue.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.babelqueue.Envelope;
import com.babelqueue.EnvelopeCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutboxTest {

    /** A fake transport that records every publish and can be told to fail specific queues/passes. */
    private static final class FakeTransport implements OutboxTransport {
        record Sent(String queue, byte[] body) {}

        final List<Sent> sent = new ArrayList<>();
        int failFirstN; // throw on the first N publish attempts, then succeed
        int attempts;

        @Override
        public void publish(String queue, byte[] body) throws Exception {
            attempts++;
            if (failFirstN > 0) {
                failFirstN--;
                throw new IllegalStateException("broker down");
            }
            sent.add(new Sent(queue, body));
        }
    }

    /** Records every backoff sleep so a test can assert the budget without real waiting. */
    private static final class RecordingSleeper implements OutboxRelay.Sleeper {
        final List<Long> slept = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            slept.add(millis);
        }
    }

    private static Envelope orderEnvelope() {
        Map<String, Object> data = new HashMap<>();
        data.put("order_id", 1042L);
        return EnvelopeCodec.make("urn:babel:orders:created", data, "orders", "trace-abc-123");
    }

    @Test
    void writeStoresTheEncodedEnvelopeBytesVerbatim() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        Outbox outbox = new Outbox(store);
        Envelope env = orderEnvelope();

        String id = outbox.write(env);

        byte[] expected = EnvelopeCodec.encode(env).getBytes(StandardCharsets.UTF_8);
        List<OutboxRecord> pending = store.fetchUnpublished(10);
        assertEquals(1, pending.size());
        OutboxRecord record = pending.get(0);
        assertEquals(id, record.id());
        assertEquals("orders", record.queue());
        // GR-1/GR-5: the stored bytes are byte-identical to the codec output.
        assertArrayEquals(expected, record.body());
    }

    @Test
    void writeFallsBackToDefaultQueueWhenEnvelopeHasNone() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        Outbox outbox = new Outbox(store);
        Envelope env = EnvelopeCodec.make("urn:babel:plain", Map.of(), null, null);

        outbox.write(env);

        assertEquals(Outbox.DEFAULT_QUEUE, store.fetchUnpublished(10).get(0).queue());
    }

    @Test
    void relayPublishesViaTransportAndMarksPublished() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        Outbox outbox = new Outbox(store);
        Envelope env = orderEnvelope();
        outbox.write(env);

        FakeTransport transport = new FakeTransport();
        OutboxRelay relay = new OutboxRelay(transport, store, 100, 50, 5000, new RecordingSleeper());

        OutboxRelayResult result = relay.flush();

        assertEquals(1, result.published());
        assertEquals(0, result.failed());
        assertEquals(1, result.attempted());
        assertEquals(0, store.pendingCount());
        assertEquals(1, transport.sent.size());
        assertEquals("orders", transport.sent.get(0).queue());
    }

    @Test
    void relayPublishesTheStoredBytesVerbatimSoTraceIdSurvives() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        Outbox outbox = new Outbox(store);
        Envelope env = orderEnvelope();
        byte[] storedBytes = EnvelopeCodec.encode(env).getBytes(StandardCharsets.UTF_8);
        outbox.write(env);

        FakeTransport transport = new FakeTransport();
        new OutboxRelay(transport, store, 100, 50, 5000, new RecordingSleeper()).flush();

        // GR-4/GR-5: the bytes that reached the broker are byte-identical to what was stored,
        // and decoding them recovers the original trace_id without the relay ever decoding.
        byte[] published = transport.sent.get(0).body();
        assertArrayEquals(storedBytes, published);
        Envelope roundTripped = EnvelopeCodec.decode(new String(published, StandardCharsets.UTF_8));
        assertEquals("trace-abc-123", roundTripped.traceId());
    }

    @Test
    void aThrowingPublishMarksFailedLeavesRowPendingAndDoesNotBlockTheBatch() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        Outbox outbox = new Outbox(store);
        String poisonId = outbox.write(orderEnvelope());
        outbox.write(orderEnvelope()); // a healthy row behind the poison one

        FakeTransport transport = new FakeTransport();
        transport.failFirstN = 1; // only the first (poison) publish throws
        OutboxRelay relay = new OutboxRelay(transport, store, 100, 50, 5000, new RecordingSleeper());

        OutboxRelayResult result = relay.flush();

        // The poison row failed; the row behind it still published — the batch continued.
        assertEquals(1, result.published());
        assertEquals(1, result.failed());
        assertEquals(1, transport.sent.size());
        // The poison row is left pending with an incremented attempt count and a recorded error.
        assertEquals(1, store.pendingCount());
        assertEquals(1, store.attemptsOf(poisonId));
        assertTrue(store.lastErrorOf(poisonId).contains("broker down"));
        assertFalse(store.lastErrorOf(poisonId).isBlank());
    }

    @Test
    void drainRetriesAcrossPassesUntilTheOutboxIsEmpty() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        Outbox outbox = new Outbox(store);
        outbox.write(orderEnvelope());
        outbox.write(orderEnvelope());

        FakeTransport transport = new FakeTransport();
        // Fail the first publish; the row stays pending and a later pass succeeds.
        transport.failFirstN = 1;
        OutboxRelay relay = new OutboxRelay(transport, store, 100, 50, 5000, new RecordingSleeper());

        OutboxRelayResult result = relay.drain(0);

        assertEquals(2, result.published());
        assertEquals(1, result.failed());
        assertEquals(0, store.pendingCount());
    }

    @Test
    void drainStopsWhenOnlyFailingRowsRemain() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        Outbox outbox = new Outbox(store);
        outbox.write(orderEnvelope());

        FakeTransport transport = new FakeTransport();
        transport.failFirstN = Integer.MAX_VALUE; // every publish throws
        RecordingSleeper sleeper = new RecordingSleeper();
        OutboxRelay relay = new OutboxRelay(transport, store, 100, 50, 5000, sleeper);

        OutboxRelayResult result = relay.drain(0);

        // First pass made no progress (published 0) → drain stops immediately rather than spinning.
        assertEquals(0, result.published());
        assertEquals(1, result.failed());
        assertEquals(1, store.pendingCount());
        assertEquals(1, sleeper.slept.size());
    }

    @Test
    void backoffGrowsLinearlyPerAttemptAndIsCapped() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        Outbox outbox = new Outbox(store);
        String id = outbox.write(orderEnvelope());

        FakeTransport transport = new FakeTransport();
        transport.failFirstN = Integer.MAX_VALUE; // always fail so each pass records a backoff
        RecordingSleeper sleeper = new RecordingSleeper();
        // step=100ms, cap=250ms: attempts 0,1,2,3 → 100, 200, 250(capped from 300), 250(capped).
        OutboxRelay relay = new OutboxRelay(transport, store, 1, 100, 250, sleeper);

        relay.flush(); // row attempts 0 → backoff 100
        relay.flush(); // row attempts 1 → backoff 200
        relay.flush(); // row attempts 2 → backoff min(300,250)=250
        relay.flush(); // row attempts 3 → backoff min(400,250)=250

        assertEquals(List.of(100L, 200L, 250L, 250L), sleeper.slept);
        assertEquals(4, store.attemptsOf(id));
    }

    @Test
    void fetchUnpublishedReturnsOldestFirstAndRespectsTheLimit() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        Outbox outbox = new Outbox(store);
        String first = outbox.write(orderEnvelope());
        String second = outbox.write(orderEnvelope());
        outbox.write(orderEnvelope());

        List<OutboxRecord> batch = store.fetchUnpublished(2);

        assertEquals(2, batch.size());
        assertEquals(first, batch.get(0).id());
        assertEquals(second, batch.get(1).id());
    }
}
