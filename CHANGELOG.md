# Changelog

All notable changes to `com.babelqueue:babelqueue-core` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The envelope wire format is versioned separately by `meta.schema_version`
(currently **1**) — see the contract at [babelqueue.com](https://babelqueue.com).

## [Unreleased]

## [1.5.0] - 2026-06-21

### Added
- **W3C `traceparent` transport-header propagation** (ADR-0028, the v0.2 follow-up to
  ADR-0025) in the optional `com.babelqueue.otel` module — true cross-hop span
  parent-child linkage layered over the existing v0.1 `trace_id` ↔ OTel-trace-id
  mapping. The producer injects the active span's `traceparent` (and `tracestate`)
  onto an out-of-band `Map<String, String>` header carrier that rides **beside** the
  frozen envelope (GR-1, `schema_version` stays `1`), never inside it; the consumer
  reads it and starts its `process <urn>` span as a true child of the producer span.
  With no `traceparent` present it falls back to the v0.1 `trace_id`-derived parent —
  a strict, backward-compatible upgrade, no regression.
  - New produce-side seam `otel.HeaderSender` (a `Sender` that also receives the
    out-of-band header map) plus `Tracing.publish(..., HeaderSender)` overloads.
  - New consume-side overload `Tracing.wrapHandler(tracer, handler,
    Supplier<Map<String,String>>)` that reads a delivered message's out-of-band
    headers (e.g. `Redrive.Reserved.headers()`).
  - The W3C wire format is produced by OpenTelemetry's own `W3CTraceContextPropagator`
    (shipped in `opentelemetry-api`, the **already-optional** dependency), so **no new
    dependency** is pulled and the core stays zero-dependency for non-opt-in users
    (GR-7); `trace_id` is preserved unchanged (GR-4).
  - **Transport wiring is a documented follow-up**: the Java transports live in
    separate artifacts (`babelqueue-java-sqs`, `babelqueue-java-redis`,
    `babelqueue-spring`), so carrying the header map on each broker's native
    per-message metadata channel (AMQP headers, SQS `MessageAttributes`, a Redis
    transport-owned frame) is the per-transport rollout — the same seam ADR-0027 and
    the broker bindings roll out per SDK.

## [1.0.0] - 2026-06-07

**1.0.0 — the public API is now SemVer-stable**: breaking changes require a MAJOR,
following the deprecation policy. The wire envelope is unchanged
(`schema_version: 1`). Full reference at [babelqueue.com](https://babelqueue.com).

### Internal
- Build adds **JaCoCo** (line-coverage gate ≥90%, bound to `verify`) and
  **SpotBugs** (`effort=Max`, `threshold=Medium`); both run in CI via `mvn verify`.
  Added JSON codec edge-case + exception tests to clear the gate. A documented
  `spotbugs-exclude.xml` waives the EI_EXPOSE patterns on the read-only envelope
  records (no hot-path defensive copy — GR-8).
- **GR-8 latency benchmark** (`OverheadBenchmarkTest`) — asserts the envelope
  encode/decode path adds **≤2%** over plain-JSON serialization vs a conservative
  750µs broker round-trip.

## [0.1.0] - 2026-06-06

### Added
- `EnvelopeCodec` — builds (`make`, `fromMessage`), encodes and decodes the
  canonical `{job, trace_id, data, meta, attempts}` envelope (`schema_version` 1).
  The single Java implementation of the wire format.
- `Envelope` / `Meta` / `DeadLetter` immutable `record` types.
- `EnvelopeCodec.encode` emits compact UTF-8 JSON (slashes/unicode unescaped) —
  byte-identical to the PHP, Python and Node cores (insertion order preserved).
- `EnvelopeCodec.urn(...)` — resolve the URN (`job`, accepting `urn` as an alias).
- `EnvelopeCodec.accepts(...)` — consumer-side validation (rejects empty URN,
  unsupported `meta.schema_version`, missing `data`, blank `trace_id`).
- `DeadLetters.annotate(...)` — additive `dead_letter` block builder.
- Contracts `PolyglotMessage` / `HasTraceId`.
- `UnknownUrnStrategy` (`FAIL` / `DELETE` / `RELEASE` / `DEAD_LETTER`);
  `BabelQueueException` / `UnknownUrnException`.
- A built-in minimal JSON reader/writer so the core ships with **zero
  dependencies** — no Jackson/Gson forced on consumers.
- Shared cross-SDK **conformance suite** under `src/test/resources/conformance/`
  (vendored from the canonical `conformance/` set) plus a runner.

### Notes
- Pre-1.0: the public API may change before the `1.0.0` tag.
- **Zero runtime dependencies** (pure JDK); requires Java **17+**.

[Unreleased]: https://github.com/BabelQueue/babelqueue-java/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/BabelQueue/babelqueue-java/compare/v0.1.0...v1.0.0
[0.1.0]: https://github.com/BabelQueue/babelqueue-java/releases/tag/v0.1.0
