package com.babelqueue;

import java.util.Map;

/**
 * Replay-Bypass — a side-effect guard for DLQ replay (ADR-0027).
 *
 * <p>A deliberate replay off the dead-letter queue ({@link Redrive}) re-runs the handler, and
 * the handler's external side-effects re-fire: a second charge, a duplicate email.
 * {@code Idempotent.wrap} stops an <i>accidental</i> duplicate; it does not stop the
 * <i>intended</i> reprocess from re-firing effects that already happened. This closes that gap.
 *
 * <p>The marker that says "this is a replay, skip the external effects" rides <b>out of band</b>
 * as the {@link #HEADER_REPLAY_BYPASS} transport header — never in the frozen envelope (GR-1).
 * {@link Redrive#redrive} stamps it when its options set {@code bypass} and the transport is a
 * {@link Redrive.HeaderPublisher}; a consume adapter, having reserved the message with its
 * headers, surfaces the flag for the duration of the handler via {@link #process}:
 *
 * <pre>{@code
 * Redrive.Reserved msg = transport.pop(queue);
 * Envelope env = EnvelopeCodec.decode(msg.body());
 * Replay.process(msg.headers(), () -> handler.handle(env)); // handler can now query isReplay()
 * }</pre>
 *
 * <p>A handler wraps its external, non-idempotent side in {@link #bypassExternalEffects} so a
 * replay re-runs the idempotent core but skips effects that already fired. The Java core is
 * codec-only, so this is the core/runtime API + in-memory testing; a concrete broker transport
 * carries the header once it implements {@link Redrive.HeaderPublisher} (a follow-up).
 */
public final class Replay {

    /**
     * The out-of-band transport header {@link Redrive} stamps (with {@code bypass}) on a replayed
     * message, and that a consume adapter surfaces to the handler via {@link #process}.
     */
    public static final String HEADER_REPLAY_BYPASS = "bq-replay-bypass";

    private static final ThreadLocal<Boolean> REPLAY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Replay() {
    }

    /** An action that may throw — the external side of a handler. */
    @FunctionalInterface
    public interface Effect {
        void run() throws Exception;
    }

    /**
     * Reports whether the message currently being handled was redriven with the replay-bypass
     * marker — i.e. a deliberate replay whose external side-effects should be skipped. Meaningful
     * only inside a {@link #process} scope the consumer established from the message's headers.
     *
     * @return whether the current handling is a bypassed replay
     */
    public static boolean isReplay() {
        return REPLAY.get();
    }

    /**
     * Runs {@code body} with the replay flag derived from a reserved message's transport headers
     * (the presence of {@link #HEADER_REPLAY_BYPASS}), restoring the prior flag afterwards. A
     * consume adapter wraps each handler invocation in this.
     *
     * @param headers the reserved message's out-of-band headers (may be null/empty)
     * @param body    the handler invocation to run within the replay scope
     * @throws Exception whatever {@code body} throws
     */
    public static void process(Map<String, String> headers, Effect body) throws Exception {
        boolean replay = headers != null && headers.containsKey(HEADER_REPLAY_BYPASS);
        boolean previous = REPLAY.get();
        REPLAY.set(replay);
        try {
            body.run();
        } finally {
            REPLAY.set(previous);
        }
    }

    /**
     * Runs {@code effect} unless the current message is a {@linkplain #isReplay replay}, in which
     * case it is skipped. Wrap the external, non-idempotent side of a handler — sending an email,
     * charging a card, calling a third party — so a replay re-runs the idempotent core but does
     * not re-fire effects that already happened.
     *
     * @param effect the external side-effect to run only when this is not a replay
     * @throws Exception whatever {@code effect} throws
     */
    public static void bypassExternalEffects(Effect effect) throws Exception {
        if (!isReplay()) {
            effect.run();
        }
    }
}
