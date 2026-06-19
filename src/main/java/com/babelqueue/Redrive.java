package com.babelqueue;

import java.util.ArrayList;
import java.util.List;
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
 * <p>Safety in v1 is {@code dryRun} + sandbox routing ({@code toQueue}) + {@code select}. The
 * <b>Replay-Bypass</b> guard (a {@code bq-replay-bypass} transport header surfaced to handlers
 * so a replay can skip external side-effects) is a documented phase two — it touches the
 * runtime and every transport, like ADR-0025's {@code traceparent} follow-up.
 */
public final class Redrive {

    private Redrive() {
    }

    /** A reserved DLQ message: its raw body plus a transport-specific handle used to ack it. */
    public record Reserved(String body, Object handle) {
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
     * Options for a {@link #redrive} run; immutable, built with the fluent withers from
     * {@link #all()}.
     *
     * @param toQueue overrides the target queue (sandbox/redirect); when blank, each message
     *                goes back to its own {@code dead_letter.original_queue}
     * @param max     caps how many messages are pulled from the DLQ (0 = all available)
     * @param dryRun  inspect and report the plan, restoring every message unchanged
     * @param select  picks which messages to redrive (unselected are restored unchanged)
     */
    public record Options(String toQueue, int max, boolean dryRun, Predicate<Envelope> select) {

        /** Redrive every message back to its source queue. */
        public static Options all() {
            return new Options(null, 0, false, null);
        }

        public Options toQueue(String queue) {
            return new Options(queue, max, dryRun, select);
        }

        public Options max(int limit) {
            return new Options(toQueue, limit, dryRun, select);
        }

        public Options dryRun(boolean enabled) {
            return new Options(toQueue, max, enabled, select);
        }

        public Options select(Predicate<Envelope> predicate) {
            return new Options(toQueue, max, dryRun, predicate);
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
        boolean redriven
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
     * {@code meta} are preserved verbatim.
     */
    public static Envelope reset(Envelope env) {
        return new Envelope(env.job(), env.traceId(), env.data(), env.meta(), 0, null);
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
            // decode() never throws — a malformed/non-object body yields an empty envelope
            // (a null job), which is not redrivable; restore it rather than drop it.
            Envelope env = EnvelopeCodec.decode(msg.body());
            if (env.job() == null || env.job().isBlank()) {
                transport.publish(dlq, msg.body());
                transport.ack(msg);
                skipped++;
                items.add(new Item(null, null, null, null, dlq, null, false));
                continue;
            }

            String reason = env.deadLetter() == null ? null : env.deadLetter().reason();
            String messageId = env.meta() == null ? null : env.meta().id();

            if (opts.select() != null && !opts.select().test(env)) {
                transport.publish(dlq, msg.body());
                transport.ack(msg);
                skipped++;
                items.add(new Item(messageId, env.traceId(), env.job(), reason, dlq, null, false));
                continue;
            }

            String target = opts.toQueue() != null && !opts.toQueue().isBlank()
                ? opts.toQueue()
                : sourceQueueOf(env);

            if (opts.dryRun()) {
                transport.publish(dlq, msg.body());
                transport.ack(msg);
                skipped++;
                items.add(new Item(messageId, env.traceId(), env.job(), reason, dlq, target, false));
                continue;
            }

            try {
                transport.publish(target, EnvelopeCodec.encode(reset(env)));
            } catch (Exception publishFailure) {
                transport.publish(dlq, msg.body());
                transport.ack(msg);
                throw publishFailure;
            }
            transport.ack(msg);
            redriven++;
            items.add(new Item(messageId, env.traceId(), env.job(), reason, dlq, target, true));
        }

        return new Result(redriven, skipped, items);
    }

    private static String sourceQueueOf(Envelope env) {
        DeadLetter dl = env.deadLetter();
        if (dl != null && dl.originalQueue() != null && !dl.originalQueue().isBlank()) {
            return dl.originalQueue();
        }
        return env.meta() == null ? null : env.meta().queue();
    }
}
