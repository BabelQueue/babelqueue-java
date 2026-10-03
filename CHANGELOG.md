# Changelog

All notable changes to `com.babelqueue:babelqueue-core` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The envelope wire format is versioned separately by `meta.schema_version`
(currently **1**) — see the contract at [babelqueue.com](https://babelqueue.com).

## [Unreleased]

## [1.8.0] - 2026-10-03

MINOR release: additive public API (`extras`, `withAttempts`, `withDeadLetter`, a new
`decode` overload). Note that `Envelope` and `Meta` gain a record component, so a Java 21
record *deconstruction pattern* listing the old component count (`case Envelope(var a, …)`)
must add the `extras` slot; `new Envelope(...)` / `new Meta(...)` calls with the previous
argument counts keep compiling.

### Fixed
- **Unknown keys are preserved (message-envelope.md §4/§8, GR-5).** `Envelope` and `Meta`
  gain an `extras` component (`Map<String,Object>`, insertion-ordered, unmodifiable, never
  `null`): `EnvelopeCodec.decode` collects unknown top-level and `meta` keys into it, and
  `encode` writes the known fields in their canonical order first, then the extras. Every
  re-emit path carries them: retry (`Envelope.withAttempts`), DLQ (`DeadLetters.annotate`)
  and redrive (`Redrive.reset`); the outbox relay already publishes the stored bytes verbatim.
  Previously a decode → re-encode hop silently dropped them.
- **Forbidden keys (§10, K-15).** `timestamp`, `meta.max_retries`, `meta.attempts`,
  `meta.source` and `meta.ts` are never captured into extras and never encoded. Decode does
  not reject them: it drops them and emits a warning naming the JSON pointer (e.g.
  `/meta/attempts`) — via `System.Logger` at `WARNING`, or to the callback of the new
  `EnvelopeCodec.decode(String, Consumer<String>)` overload.
- **`minLength` counts Unicode code points** (ADR-0024), not UTF-16 code units — an emoji is
  one character, matching every other SDK's payload validator.

### Added
- Additive API: `Envelope(..., extras)` / `Meta(..., extras)` canonical constructors (the
  previous 6-arg / 5-arg constructors remain and yield empty extras),
  `Envelope.withAttempts(int)`, `Envelope.withDeadLetter(DeadLetter)`,
  `EnvelopeCodec.decode(String, Consumer<String>)`.
- Conformance runners for the `roundtrip`, `data_shape`, `forbidden_keys` and
  `payload_schema_unicode` manifest sections (none may be skipped).
- `.github/dependabot.yml` (Maven + GitHub Actions, weekly).

## [1.7.0] - 2026-06-21

### Added
- **Runtime GDPR field encryption** (ADR-0030) in the new optional `com.babelqueue.gdpr`
  module — the **SDK-enforcement** half of the registry's `x-gdpr-sensitive` declaration.
  babelqueue-registry only *declares* and audits which `data` fields are personal data; this
  module *enforces* it on the wire: a producer encrypts each marked leaf before publish, a
  consumer decrypts it after decode. It is the Java mirror of the Go reference, so every SDK
  round-trips byte-for-byte. Standalone and **opt-in**.
  - `Cipher` is a **caller-provided** interface (`encrypt(byte[])` / `decrypt(String)`) — a
    seam onto KMS/Vault/HSM/tokenisation, so the core pulls **no** crypto dependency (GR-7).
    `AesGcmCipher` is a JDK-only reference (`javax.crypto`, AES-256-GCM, a fresh random 12-byte
    IV prepended, Base64); the caller owns the key. A wrong key or tampered ciphertext fails GCM
    authentication and throws rather than returning corrupt plaintext.
  - `Gdpr.protect(data, schema, cipher)` / `Gdpr.unprotect(...)` rewrite each `x-gdpr-sensitive`
    leaf **in place**: the value is canonically JSON-encoded then replaced by the ciphertext
    **string**, and `unprotect` decodes the decrypted bytes back — so the round-trip is
    **byte-for-byte** (a number restores to a number, an object to an object). An absent path is
    skipped; a non-string leaf in `unprotect` is left untouched (idempotent re-runs); a value the
    cipher cannot open throws `DecryptException` so the message takes retry / dead-letter.
  - `SensitivePaths.of(schema)` (+ the `SensitivePath` record) in `com.babelqueue.schema` walk a
    decoded JSON Schema for the `x-gdpr-sensitive` marks (boolean `true` or a non-empty string
    category), descending nested objects, array items (`field[]`) and the root. The keyword is
    **validation-neutral** — annotating a schema is never a breaking change.
  - The wire envelope stays **frozen**: only the *value* of a sensitive field changes (to a
    ciphertext string), so `data` is still pure JSON (GR-3), `meta.schema_version` stays `1` and
    `trace_id` is untouched (GR-4). This is additive and opt-in.

## [1.6.0] - 2026-06-21

### Added
- **Transactional-outbox helper** (ADR-0029) in the new optional `com.babelqueue.outbox`
  module — the **producer-side** mirror of the consumer-side idempotency helper
  (ADR-0022). It removes the producer *dual write*: the message is persisted into the
  caller's own database, **inside the caller's own transaction**, so it commits or rolls
  back atomically with the business data, and a separate relay publishes the durable rows
  afterwards. Exactly-once *handoff* into the broker, then at-least-once on the wire as
  always (consumers still dedupe on `meta.id`).
  - `Outbox.write(envelope)` encodes via the frozen `EnvelopeCodec` and hands the **bytes
    verbatim** to the store — the outbox stores the wire envelope byte-for-byte and never
    adds an envelope field (GR-1, `schema_version` stays `1`); `trace_id` is preserved
    end-to-end (GR-4) and the relay publishes those exact bytes (GR-5).
  - `OutboxStore` is the persistence contract the caller binds to their own DB (JDBC) —
    the core adds **no** DB driver (GR-7); `InMemoryOutboxStore` is the reference for
    tests/single-process demos. **The transaction boundary is the caller's**: `Outbox`
    never begins/commits.
  - `OutboxRelay.flush()`/`drain(maxPasses)` publish a batch through the publish-only
    `OutboxTransport` seam, marking a row published **only after** the transport accepts
    it; a throwing publish is caught, recorded via `markFailed` with a bounded linear
    backoff (injectable `Sleeper` so tests stay instant), and the row stays pending — one
    poison row never blocks the batch.
  - The envelope wire format is unchanged; this is an additive, opt-in producer-side
    concern.

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
