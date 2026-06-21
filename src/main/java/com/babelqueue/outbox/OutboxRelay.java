package com.babelqueue.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The <b>read/publish side</b> of the transactional outbox (ADR-0029): drain pending rows the
 * {@link Outbox} writer committed and forward each onto the broker through the {@link OutboxTransport}
 * seam, marking every row published or failed.
 *
 * <p>Run it on a short interval (a worker loop, a scheduled command) <em>after</em> the business
 * transaction commits. Because the message was committed atomically with the business data, the
 * relay is the only thing standing between "row exists" and "broker has it" — and it only ever reads
 * already-durable rows, so it never invents work.
 *
 * <p><b>Semantics — at-least-once handoff:</b>
 * <ul>
 *   <li>A row is marked <b>published only after</b> {@link OutboxTransport#publish(String, byte[])}
 *       returns; if the process dies between publish and {@link OutboxStore#markPublished(List)},
 *       the row stays pending and is published <b>again</b> on the next pass. That is at-least-once:
 *       a downstream consumer must dedupe on the canonical {@code meta.id}
 *       ({@code com.babelqueue.idempotency.Idempotent} is exactly that guard, the consumer-side
 *       mirror of this producer-side helper — ADR-0022).</li>
 *   <li>A publish that <b>throws</b> is caught, {@link OutboxStore#markFailed(String, String)}
 *       records the error and bumps the attempt count, and the row stays pending for a later retry.
 *       One poison row never blocks the rest of the batch.</li>
 *   <li><b>{@code trace_id} is preserved end-to-end</b> (GR-4): the relay publishes the stored bytes
 *       <em>verbatim</em> — it never decodes, rebuilds or re-encodes the envelope — so the body that
 *       reaches the broker is byte-identical to what was stored (GR-1/GR-5).</li>
 * </ul>
 *
 * <p><b>Backoff:</b> between a failed publish and the next attempt within the same pass the relay
 * sleeps for a bounded, linearly-growing delay (capped), to avoid hammering a broker that is briefly
 * down. The {@link Sleeper} is injectable so tests stay instant.
 */
public final class OutboxRelay {

    /** Hard safety ceiling on {@link #drain(int)} passes when the caller passes a non-positive value. */
    public static final int DEFAULT_DRAIN_CEILING = 10_000;

    /** Default number of rows reserved and published per {@link #flush()}. */
    public static final int DEFAULT_BATCH_SIZE = 100;

    /** Default base backoff added per prior attempt, in milliseconds. */
    public static final int DEFAULT_BACKOFF_STEP_MS = 50;

    /** Default upper bound on a single backoff sleep, in milliseconds. */
    public static final int DEFAULT_BACKOFF_CAP_MS = 5_000;

    /** Sleeps for a number of milliseconds between a failed publish and the next attempt. */
    @FunctionalInterface
    public interface Sleeper {
        /** Sleep {@code millis} milliseconds (a no-op for {@code millis <= 0}). */
        void sleep(long millis) throws InterruptedException;
    }

    private final OutboxTransport transport;
    private final OutboxStore store;
    private final int batchSize;
    private final int backoffStepMs;
    private final int backoffCapMs;
    private final Sleeper sleeper;

    /**
     * A relay with the default batch size and backoff budget, using a real {@link Thread#sleep}.
     *
     * @param transport where published rows go (the publish-only seam)
     * @param store     the outbox to drain
     */
    public OutboxRelay(OutboxTransport transport, OutboxStore store) {
        this(transport, store, DEFAULT_BATCH_SIZE, DEFAULT_BACKOFF_STEP_MS, DEFAULT_BACKOFF_CAP_MS, null);
    }

    /**
     * A fully configured relay.
     *
     * @param transport     where published rows go (the same publish-only seam every framework-less
     *                      producer uses)
     * @param store         the outbox to drain
     * @param batchSize     how many rows to reserve and publish per {@link #flush()}
     * @param backoffStepMs base backoff added per prior attempt, in milliseconds
     * @param backoffCapMs  upper bound on a single backoff sleep, in milliseconds
     * @param sleeper       sleeps the backoff between attempts; {@code null} uses {@link Thread#sleep}.
     *                      Inject a no-op (or a recorder) in tests so they stay instant.
     */
    public OutboxRelay(
        OutboxTransport transport,
        OutboxStore store,
        int batchSize,
        int backoffStepMs,
        int backoffCapMs,
        Sleeper sleeper
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.store = Objects.requireNonNull(store, "store");
        this.batchSize = batchSize;
        this.backoffStepMs = backoffStepMs;
        this.backoffCapMs = backoffCapMs;
        this.sleeper = sleeper != null ? sleeper : OutboxRelay::threadSleep;
    }

    /**
     * Publish one batch of pending rows. Each row the transport accepts is marked published; each
     * that throws is marked failed (with a backoff before continuing) and left pending. Returns a
     * per-pass tally. Call it repeatedly (a loop / cron) to drain the outbox; {@link #drain(int)}
     * loops until it is empty.
     */
    public OutboxRelayResult flush() {
        List<OutboxRecord> records = store.fetchUnpublished(batchSize);

        List<String> publishedIds = new ArrayList<>();
        int failed = 0;

        for (OutboxRecord record : records) {
            try {
                // Publish the STORED BYTES VERBATIM — never decode/rebuild/re-encode (GR-1/GR-4/GR-5).
                transport.publish(record.queue(), record.body());
                publishedIds.add(record.id());
            } catch (Exception e) {
                store.markFailed(record.id(), reason(e));
                failed++;
                sleep(backoffFor(record.attempts()));
            }
        }

        if (!publishedIds.isEmpty()) {
            store.markPublished(publishedIds);
        }

        return new OutboxRelayResult(publishedIds.size(), failed);
    }

    /**
     * Drain the outbox by repeatedly calling {@link #flush()} while each pass keeps making progress
     * (publishes at least one row), then return the cumulative tally. The loop stops as soon as a
     * pass publishes nothing — the outbox is empty, or only currently-failing rows remain (those are
     * left pending for a future {@code drain} call once the broker recovers). {@code maxPasses} is a
     * hard safety ceiling so a degenerate store can never spin forever (a non-positive value uses
     * {@link #DEFAULT_DRAIN_CEILING}).
     */
    public OutboxRelayResult drain(int maxPasses) {
        int ceiling = maxPasses > 0 ? maxPasses : DEFAULT_DRAIN_CEILING;
        int published = 0;
        int failed = 0;

        for (int pass = 0; pass < ceiling; pass++) {
            OutboxRelayResult result = flush();
            published += result.published();
            failed += result.failed();

            // No progress this pass → drained, or only failing rows remain. Stop.
            if (result.published() == 0) {
                break;
            }
        }

        return new OutboxRelayResult(published, failed);
    }

    /**
     * The backoff (ms) for a row that has already failed {@code priorAttempts} times: a linear step
     * per attempt, capped. Kept simple and deterministic so the budget is obvious.
     */
    private int backoffFor(int priorAttempts) {
        long delay = (long) backoffStepMs * Math.max(1, priorAttempts + 1);
        return (int) Math.min(delay, backoffCapMs);
    }

    private void sleep(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void threadSleep(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    /** A short, safe failure reason from a thrown error (class + message, no stack). */
    private static String reason(Exception e) {
        String message = e.getMessage();
        return e.getClass().getName() + (message != null ? ": " + message : "");
    }
}
