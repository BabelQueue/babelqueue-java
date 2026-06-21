package com.babelqueue.outbox;

/**
 * Summary of an {@link OutboxRelay} run: how many pending rows were published and how many failed
 * (and were left pending for a later retry). Returned by both {@link OutboxRelay#flush()} (one pass)
 * and {@link OutboxRelay#drain(int)} (cumulative over all passes).
 *
 * @param published rows the transport accepted and that were marked published
 * @param failed    rows whose publish threw; left pending for a later retry
 */
public record OutboxRelayResult(int published, int failed) {

    /** Total rows the relay attempted (published + failed). */
    public int attempted() {
        return published + failed;
    }
}
