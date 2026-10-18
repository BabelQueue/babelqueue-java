package com.babelqueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * DLQ redrive tooling — safe replay off the dead-letter queue (ADR-0026).
 *
 * <p>The operator-side counterpart to the runtime's dead-letter routing: it reads dead-lettered
 * messages off a DLQ and re-publishes each to its source queue (its {@code dead_letter
 * .original_queue}) or to a chosen queue, {@linkplain #reset(Envelope) reset for reprocessing}
 * — the {@code dead_letter} block removed and {@code attempts} reset to 0, while {@code job},
 * {@code trace_id}, {@code data} and {@code meta} are preserved verbatim.
 *
 * <p>The Java core is codec-only (transports are separate artifacts), so {@link #redrive} works
 * over a minimal {@link Transport} the caller implements over their broker — the same shape as
 * the {@code otel} module's {@code Sender}. The wire envelope stays frozen (GR-1) and no
 * dependency is added.
 *
 * <p>Safety is {@code dryRun} + sandbox routing ({@code toQueue}) + {@code select}. The
 * {@code bypass} option stamps a {@code bq-replay-bypass} transport header (see {@link Replay})
 * so a handler can skip external side-effects on a replay; it takes effect on transports that
 * implement {@link HeaderPublisher} (ADR-0027). A transport that implements
 * {@link KeyedRepublisher} (Kafka: the record key, contract §6.5) is handed the reserved DLQ
 * message for every re-publish — the redrive write and the restore write back to the DLQ — so
 * broker-native per-message metadata is carried over; any other transport gets a plain publish.
 *
 * <p>When the restore write itself fails, the reserved message must not be lost: it is never
 * acknowledged, and a transport that implements {@link Rewinder} (Kafka, where a commit is
 * cumulative and a later commit could otherwise skip the record) is asked to hand it back to the
 * broker. If the rewind also fails, both errors are reported together. The same hook runs whenever
 * the run is stopped by an exception (a redrive write that failed after a successful restore, a
 * throwing {@code select} predicate, a failed ack): the rest of the already-reserved batch is handed
 * back too and nothing later can skip it.
 */
public final class Redrive {

    private Redrive() {
    }

    /**
     * A reserved DLQ message: its raw body, a transport-specific handle used to ack it, and any
     * out-of-band transport headers (e.g. the replay-bypass marker; empty when none).
     */
    public record Reserved(String body, Object handle, Map<String, String> headers) {

        /** A reserved message with no out-of-band headers. */
        public Reserved(String body, Object handle) {
            this(body, handle, Map.of());
        }

        public Reserved {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** The minimal transport surface {@link #redrive} needs, implemented over any broker. */
    public interface Transport {

        /** Reserve the next message from {@code queue}, or {@code null} when it is empty. */
        Reserved pop(String queue) throws Exception;

        /** Publish an already-encoded {@code body} to {@code queue}. */
        void publish(String queue, String body) throws Exception;

        /** Acknowledge (remove) a previously reserved message. */
        void ack(Reserved message) throws Exception;
    }

    /**
     * An optional {@link Transport} capability: publish a body together with out-of-band transport
     * headers (e.g. the replay-bypass marker), for brokers that carry per-message metadata. A
     * transport that does not implement it simply does not propagate headers — {@link #redrive}
     * falls back to plain {@link Transport#publish} (ADR-0027).
     */
    public interface HeaderPublisher {
        void publishWithHeaders(String queue, String body, Map<String, String> headers) throws Exception;
    }

    /**
     * An optional {@link Transport} capability: re-publish a body on behalf of a message the
     * transport itself reserved, carrying whatever native per-message metadata the broker
     * attaches that plain {@link Transport#publish} cannot express — for Kafka, the record key
     * (contract §6.5). {@link #redrive} uses it for both the write to the work topic and the
     * restore write back to the DLQ. {@code from} is the reserved DLQ message the re-publish
     * stands in for, never {@code null}. A transport that does not implement it gets a plain
     * {@link Transport#publish}, so the metadata is simply not carried.
     */
    public interface KeyedRepublisher {
        void publishFrom(String queue, String body, Reserved from) throws Exception;
    }

    /**
     * An optional {@link Transport} capability: hand an unacknowledged reserved message back to the
     * broker so it is redelivered, and make sure nothing acknowledged afterwards can move past it.
     * {@link #redrive} calls it whenever a message stops the run (its restore write failed and it must
     * not be lost, its redrive write failed, the {@code select} predicate threw, or the ack failed) —
     * for an offset-committing broker (Kafka, where a commit is cumulative) it rewinds the consumer to
     * the message's offset. {@link #redrive} reserves the whole batch before it processes
     * it, so the implementation must also hand back every other message it reserved and has not
     * acknowledged yet (Kafka: the consumer position is already past them, and a later commit would
     * skip them).
     *
     * <p>{@code message} may itself already be acknowledged: when a redrive write fails and the
     * message was restored to the DLQ (and acknowledged), {@link #redrive} stops and calls this to
     * hand back the rest of the batch. An acknowledged {@code message} must <em>not</em> be
     * redelivered (that would duplicate it); only the reserved-but-unacknowledged messages are. It
     * must never delete or acknowledge a message and must not alter its body. A transport that does
     * not implement it keeps the previous behaviour (the message is left unacknowledged).
     */
    public interface Rewinder {
        void rewind(Reserved message) throws Exception;
    }

    /**
     * Options for a {@link #redrive} run; immutable, built with the fluent withers from
     * {@link #all()}.
     *
     * @param toQueue overrides the target queue (sandbox/redirect); when blank, each message
     *                goes back to its own {@code dead_letter.original_queue}
     * @param max     caps how many messages are pulled from the DLQ (0 = all available)
     * @param dryRun  inspect and report the plan, restoring every message unchanged
     * @param select  picks which messages to redrive (unselected are restored unchanged)
     * @param bypass  stamps the bq-replay-bypass header on each redriven message (see
     *                {@link Replay}); a no-op unless the transport is a {@link HeaderPublisher}
     */
    public record Options(String toQueue, int max, boolean dryRun, Predicate<Envelope> select, boolean bypass) {

        /** Redrive every message back to its source queue. */
        public static Options all() {
            return new Options(null, 0, false, null, false);
        }

        public Options toQueue(String queue) {
            return new Options(queue, max, dryRun, select, bypass);
        }

        public Options max(int limit) {
            return new Options(toQueue, limit, dryRun, select, bypass);
        }

        public Options dryRun(boolean enabled) {
            return new Options(toQueue, max, enabled, select, bypass);
        }

        public Options select(Predicate<Envelope> predicate) {
            return new Options(toQueue, max, dryRun, predicate, bypass);
        }

        public Options bypass(boolean enabled) {
            return new Options(toQueue, max, dryRun, select, enabled);
        }
    }

    /** What happened to one message during a {@link #redrive} run. */
    public record Item(
        String messageId,
        String traceId,
        String urn,
        String reason,
        String from,
        String to,
        boolean redriven,
        boolean bypassed
    ) {
    }

    /** Summary of a {@link #redrive} run. */
    public record Result(int redriven, int skipped, List<Item> items) {

        public Result {
            items = List.copyOf(items);
        }
    }

    /**
     * Returns a copy of {@code env} reset for reprocessing: the {@code dead_letter} block is
     * removed and {@code attempts} reset to 0; {@code job}, {@code trace_id}, {@code data} and
     * {@code meta} are preserved verbatim, as are unknown top-level keys ({@link Envelope#extras()}).
     */
    public static Envelope reset(Envelope env) {
        return new Envelope(env.job(), env.traceId(), env.data(), env.meta(), 0, null, env.extras());
    }

    /**
     * Drains dead-lettered messages off {@code dlq} and re-publishes each (reset) to its source
     * queue or {@code opts.toQueue}. Messages are drained first and then processed, so restored
     * messages (skipped, dry-run, or undecodable) are not re-encountered; a DLQ message is
     * acknowledged only after a successful re-publish, and an undecodable body is restored
     * rather than dropped.
     *
     * @return a {@link Result} with redriven/skipped counts and a per-message breakdown
     * @throws Exception if the transport fails; on a publish failure the message is restored to
     *                   the DLQ before the error is re-thrown
     */
    public static Result redrive(Transport transport, String dlq, Options opts) throws Exception {
        List<Reserved> batch = new ArrayList<>();
        while (opts.max() == 0 || batch.size() < opts.max()) {
            Reserved msg = transport.pop(dlq);
            if (msg == null) {
                break;
            }
            batch.add(msg);
        }

        int redriven = 0;
        int skipped = 0;
        List<Item> items = new ArrayList<>(batch.size());

        for (Reserved msg : batch) {
            Item item;
            try {
                item = process(transport, dlq, opts, msg);
            } catch (Exception failure) {
                // The run stops here, so the rest of the reserved batch would never be processed. Whatever
                // stopped it (a failed write, a failed restore, a throwing select predicate, a failed ack),
                // hand the batch back: msg is unacknowledged unless its redrive/restore was acknowledged
                // already, in which case it must not be redelivered. A transport that cannot rewind is
                // left as before.
                if (transport instanceof Rewinder rw) {
                    try {
                        rw.rewind(msg);
                    } catch (Exception rewindFailure) {
                        failure.addSuppressed(rewindFailure);
                    }
                }
                throw failure;
            }
            if (item.redriven()) {
                redriven++;
            } else {
                skipped++;
            }
            items.add(item);
        }

        return new Result(redriven, skipped, items);
    }

    /**
     * Processes one reserved DLQ message: redrive it, or restore it unchanged (undecodable, not selected,
     * dry run). A publish failure restores the message to the DLQ and re-throws; a failed restore write
     * leaves it unacknowledged and throws the restore error with the publish error attached.
     */
    private static Item process(Transport transport, String dlq, Options opts, Reserved msg) throws Exception {
        // decode() never throws — a malformed/non-object body yields an empty envelope
        // (a null job), which is not redrivable; restore it rather than drop it. "Blank" is the closed
        // ASCII set of ADR-0031 (c), not Unicode whitespace, so every SDK redrives the same messages.
        Envelope env = EnvelopeCodec.decode(msg.body());
        if (EnvelopeCodec.isBlankAscii(env.job())) {
            restore(transport, dlq, msg);
            return new Item(null, null, null, null, dlq, null, false, false);
        }

        String reason = env.deadLetter() == null ? null : env.deadLetter().reason();
        String messageId = env.meta() == null ? null : env.meta().id();

        if (opts.select() != null && !opts.select().test(env)) {
            restore(transport, dlq, msg);
            return new Item(messageId, env.traceId(), env.job(), reason, dlq, null, false, false);
        }

        String target = !EnvelopeCodec.isBlankAscii(opts.toQueue())
            ? opts.toQueue()
            : sourceQueueOf(env);

        if (opts.dryRun()) {
            restore(transport, dlq, msg);
            return new Item(messageId, env.traceId(), env.job(), reason, dlq, target, false, false);
        }

        boolean bypassed = false;
        try {
            String encoded = EnvelopeCodec.encode(reset(env));
            if (opts.bypass() && transport instanceof HeaderPublisher hp) {
                hp.publishWithHeaders(target, encoded, Map.of(Replay.HEADER_REPLAY_BYPASS, "1"));
                bypassed = true;
            } else {
                republish(transport, target, encoded, msg);
            }
        } catch (Exception publishFailure) {
            try {
                restore(transport, dlq, msg);
            } catch (Exception restoreFailure) {
                restoreFailure.addSuppressed(publishFailure);
                throw restoreFailure;
            }
            throw publishFailure;
        }
        transport.ack(msg);
        return new Item(messageId, env.traceId(), env.job(), reason, dlq, target, true, bypassed);
    }

    /**
     * Restore {@code msg} to {@code dlq} unchanged and acknowledge it. If the restore write fails the
     * message is never acknowledged and the restore error is re-thrown ({@link #redrive} then hands the
     * batch back to a {@link Rewinder}).
     */
    private static void restore(Transport transport, String dlq, Reserved msg) throws Exception {
        republish(transport, dlq, msg.body(), msg);
        transport.ack(msg);
    }

    /** Re-publish on behalf of {@code from}: through {@link KeyedRepublisher} when available, else plain. */
    private static void republish(Transport transport, String queue, String body, Reserved from)
        throws Exception {
        if (transport instanceof KeyedRepublisher kr) {
            kr.publishFrom(queue, body, from);
        } else {
            transport.publish(queue, body);
        }
    }

    private static String sourceQueueOf(Envelope env) {
        DeadLetter dl = env.deadLetter();
        if (dl != null && !EnvelopeCodec.isBlankAscii(dl.originalQueue())) {
            return dl.originalQueue();
        }
        return env.meta() == null ? null : env.meta().queue();
    }
}
