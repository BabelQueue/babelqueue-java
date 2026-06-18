/**
 * Optional per-URN {@code data} schema validation (ADR-0024): a dependency-free subset
 * Draft-07 validator ({@link com.babelqueue.schema.PayloadValidator}), a
 * {@link com.babelqueue.schema.SchemaProvider} (with the in-memory
 * {@link com.babelqueue.schema.MapProvider}), and the
 * {@link com.babelqueue.schema.SchemaValidation} producer/consumer helpers. Opt-in, with the
 * wire envelope frozen — the Java mirror of the Go {@code schema} package and PHP
 * {@code BabelQueue\Schema}.
 */
package com.babelqueue.schema;
