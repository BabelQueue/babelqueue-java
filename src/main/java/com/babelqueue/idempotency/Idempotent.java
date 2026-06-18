package com.babelqueue.idempotency;

import com.babelqueue.Envelope;
import com.babelqueue.Meta;

/**
 * Wraps a {@link Handler} so a message whose {@code meta.id} was already processed
 * successfully is skipped instead of run again (ADR-0022) — the Java mirror of the PHP
 * {@code Idempotent::wrap}, Go {@code idempotency.Wrap}, Python {@code wrap}, and Node
 * {@code Wrap} helpers.
 *
 * <pre>{@code
 * Store store = new InMemoryStore();
 * Handler handler = Idempotent.wrap(store, env -> process(env));
 * }</pre>
 *
 * <p>A previously-seen id returns early (so an adapter acks it and the broker stops
 * redelivering); a throwing handler leaves the id unmarked so a redelivery runs it again
 * (retry / dead-letter still apply); a message with no usable {@code meta.id} runs
 * unchanged.
 */
public final class Idempotent {

    private Idempotent() {
    }

    /**
     * Returns {@code handler} wrapped with dedupe on {@code meta.id} against {@code store}.
     *
     * @param store   the dedupe record
     * @param handler the handler to guard
     * @return the wrapped handler
     */
    public static Handler wrap(Store store, Handler handler) {
        return envelope -> {
            Meta meta = envelope.meta();
            String id = (meta == null) ? null : meta.id();

            // No usable id → cannot dedupe; run the handler unchanged.
            if (id == null || id.isEmpty()) {
                handler.handle(envelope);
                return;
            }

            // Already processed on an earlier delivery: return so the adapter acks it.
            if (store.seen(id)) {
                return;
            }

            // First success wins; a throw here leaves the id unmarked → retry/DLQ apply.
            handler.handle(envelope);
            store.remember(id);
        };
    }
}
