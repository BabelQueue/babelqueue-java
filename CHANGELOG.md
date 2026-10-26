# Changelog

All notable changes to `com.babelqueue:babelqueue-core` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The envelope wire format is versioned separately by `meta.schema_version`
(currently **1**) — see the contract at [babelqueue.com](https://babelqueue.com).

## [Unreleased]

### Added
- `PoisonRecord.build(body, originalQueue, attempts, error, exception)` (ADR-0035): builds the poison `dead_letter`
  block itself (`reason`, `error`, `exception`, `failed_at`, `original_queue`, `attempts`, `lang`) and delegates to
  `build(body, deadLetter)`, so transports no longer hand-build it.

## [1.13.0] - 2026-10-24

### Added
- `RetryBackoff` (ADR-0034 section 9): worker-side retry backoff helper,
  `d(n) = min(retry_backoff_max, retry_backoff * retry_backoff_multiplier^(n-1))` with optional
  `full` jitter; defaults (`0`, `1`, `0`, `none`) yield `0` s so unconfigured behaviour is unchanged.
  A zero base delay stays `0` even when `multiplier^(n-1)` overflows to infinity.
  Locked by the `retry_backoff` conformance section.
- `PoisonRecord.build(body, deadLetter)` (ADR-0035): the DLQ record of a poison body; a strict JSON object is
  spliced byte-for-byte, anything else becomes `{"raw", "raw_encoding"?, "dead_letter"}` with lossless base64 for
  invalid UTF-8 (never U+FFFD). Locked by the core-scope `poison_dlq` conformance section.

### Changed
- `PoisonRecord.build`: the `dead_letter` argument is now validated as a single JSON object and rejected otherwise. (`IllegalArgumentException`).
- Vendored conformance bumped to v1.6.0; CI pins `fetch.sh` by SHA-256 (`BQ_FETCH_SH_SHA256`).

## [1.12.0] - 2026-10-19

Implements ADR-0032 (conformance packaging, results format, ASCII trim and the non-string `job` rule).
The envelope stays at `schema_version: 1`.

### Changed
- Trim and blank checks in `make` (`urn`, `trace_id`), `accepts`, `Redrive` and the outbox use the closed
  ASCII set (space, `\t`, `\n`, `\v`, `\f`, `\r`); Unicode whitespace that `String.strip()` removed is no
  longer trimmed (ADR-0032 section 5). `EnvelopeCodec.isBlankAscii` is now public.
- Decode: a non-string `job` (`null`, number, boolean, object, array) is ignored with a warning and a valid
  string `urn` takes over (ADR-0032 section 6).

### Added
- Conformance runner covers the v1.5.0 sections (`trim`, `job_type`, `traceparent`) and writes
  `results.json` (ADR-0032 section 2) when `BQ_CONFORMANCE_RESULTS` is set. CI validates it and the release
  attaches it as an asset. CI fetches and verifies the conformance v1.5.0 release asset.

## [1.11.0] - 2026-10-17

Implements ADR-0031 (unknown-key preservation, producer `meta` write API, encode-reject, `urn` alias
normalisation). The envelope stays frozen at `schema_version: 1`; conformance suite 1.4.0.

### Added
- **`EnvelopeCodec.make(urn, data, queue, traceId, Map<String,Object> meta)`** — producer `meta` write API.
  Each entry is one flat `meta` key written next to the core keys (a vendor block goes under `meta.ext.<vendor>`
  as the whole `ext` value). The existing `make` overloads are unchanged.

### Changed
- **`encode` rejects instead of silently skipping.** A forbidden key (`timestamp`, top-level `urn`,
  `meta.max_retries` / `attempts` / `source` / `ts`) or a key that collides with a core key (top-level
  `job` / `trace_id` / `data` / `meta` / `attempts` / `dead_letter`; `meta.id` / `queue` / `lang` /
  `schema_version` / `created_at`) in the preserved extras, or in the `meta` passed to `make`, now throws
  `BabelQueueException` naming the key's JSON pointer. A decoded message never trips this: decode already
  drops forbidden keys.
- **`decode`: `job` and `urn` together keep `job`, drop `urn` and warn** (the warning names `/urn`, same form
  as the other forbidden-key warnings). `urn` is never retained as an extra.
- **Behaviour change — blank `job` is the closed ASCII set.** "Blank" (the `urn` fallback in `decode`, and
  `urn()` / `accepts()`) is now only space, `\t`, `\n`, `\v`, `\f`, `\r` instead of `String.isBlank()` (Unicode).
  A `job` made only of non-ASCII whitespace (U+2003, U+00A0 and so on) with a valid `urn` is therefore no
  longer routed by the `urn` handler: it is a non-blank identity, so it falls to the unknown-URN strategy
  (`fail` / `delete` / `release`). With `delete` it is removed silently; with `fail` the DLQ copy keeps the
  whitespace `job` and, since decode drops `urn`, loses its routable identity (ADR-0031 Consequences/Negative).
  This is what keeps cross-SDK parity (GR-5).
  `Redrive` follows the same set: a DLQ message whose `job` is ASCII-blank is restored, while one whose `job` is only
  Unicode whitespace is redriven like in the other SDKs (previously `Redrive` used `String.isBlank()`).
- Conformance runners implement the v1.4.0 optional fields (`forbidden_keys.expect_preserved`,
  `roundtrip.expect_absent_after_reencode` / `apply_dead_letter` / `expect_dead_letter`) and the
  `extension_write` / `encode_reject` sections.

## [1.10.0] - 2026-10-12

### Added
- **`Redrive.Rewinder`: optional transport capability for a run that stops on a failed message.** Whatever
  stops `Redrive.redrive` (a failed restore write back to the DLQ, a failed redrive write, a throwing
  `select` predicate, a failed ack), a `Redrive.Transport` that also implements `Rewinder`
  (`rewind(Reserved)`) is asked to hand back every reserved, unacknowledged message — for Kafka, where a
  commit is cumulative, so a later commit cannot skip any of them. A message whose restore failed is never
  acknowledged. `rewind(from)` may be called with an already acknowledged `from` (restored after a failed
  redrive write); it must not be redelivered. If the rewind fails too, its error is attached to the original
  error as a suppressed exception (a failed redrive write rides along the same way, the restore error
  keeping its type). A transport without it behaves exactly as before.
  Additive public API (MINOR); envelope, `dead_letter` block and headers unchanged.

### Changed

- Tests only: the vendored conformance suite is now 1.3.0 (exact-case envelope keys, last-wins duplicates: new `invalid-wrong-case-job`, `wrong-case-keys-roundtrip` and `duplicate-keys-last-wins` fixtures). No production change; the Java core already behaved this way.

## [1.9.0] - 2026-10-11

### Added
- **`Redrive.KeyedRepublisher`: optional transport capability for key-preserving redrive.**
  `Redrive.redrive` hands the reserved DLQ message to a `Redrive.Transport` that also implements
  `KeyedRepublisher` (`publishFrom(queue, body, from)`) for every re-publish it makes — the write to
  the work topic and the restore write back to the DLQ (dry run, unselected, undecodable body,
  failed redrive write) — so broker-native per-message metadata such as the Kafka record key is
  carried over (contract §6.5). A transport without it behaves exactly as before (plain
  `publish`); with `bypass` set, a `HeaderPublisher` still takes precedence for the redrive write.
  Additive public API (MINOR); envelope, `dead_letter` block and headers unchanged.

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
