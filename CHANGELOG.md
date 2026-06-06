# Changelog

All notable changes to `com.babelqueue:babelqueue-core` are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The envelope wire format is versioned separately by `meta.schema_version`
(currently **1**) — see the contract at [babelqueue.com](https://babelqueue.com).

## [Unreleased]

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

[Unreleased]: https://github.com/BabelQueue/babelqueue-java/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/BabelQueue/babelqueue-java/releases/tag/v0.1.0
