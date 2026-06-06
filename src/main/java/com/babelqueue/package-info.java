/**
 * BabelQueue — Polyglot Queues, Simplified.
 *
 * <p>The framework-agnostic Java core: the canonical wire-envelope codec
 * ({@link com.babelqueue.EnvelopeCodec}), contracts
 * ({@link com.babelqueue.PolyglotMessage}, {@link com.babelqueue.HasTraceId}) and
 * dead-letter helpers ({@link com.babelqueue.DeadLetters}). It lets a Java service
 * exchange queue messages with the PHP/Laravel, Python, Go and Node SDKs over one
 * strict JSON format — with <strong>zero dependencies</strong> (pure JDK, including
 * its own minimal JSON codec).
 *
 * <p>Full spec: <a href="https://babelqueue.com">babelqueue.com</a>
 */
package com.babelqueue;
