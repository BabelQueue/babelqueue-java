/**
 * Optional idempotency helper (ADR-0022): dedupe a consume handler on {@code meta.id}.
 *
 * <p>{@link com.babelqueue.idempotency.Idempotent#wrap} wraps a
 * {@link com.babelqueue.idempotency.Handler} so a message already processed is skipped;
 * {@link com.babelqueue.idempotency.Store} is the pluggable dedupe record, with
 * {@link com.babelqueue.idempotency.InMemoryStore} as the reference implementation. The
 * wire envelope is unchanged ({@code schema_version: 1}) — this is a pure consumer-side
 * concern. See {@code .ssot/contracts/error-handling.md} §1.
 */
package com.babelqueue.idempotency;
