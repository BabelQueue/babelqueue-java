# BabelQueue for Java

[![CI](https://github.com/BabelQueue/babelqueue-java/actions/workflows/ci.yml/badge.svg)](https://github.com/BabelQueue/babelqueue-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.babelqueue/babelqueue-core.svg)](https://central.sonatype.com/artifact/com.babelqueue/babelqueue-core)
[![javadoc](https://javadoc.io/badge2/com.babelqueue/babelqueue-core/javadoc.svg)](https://javadoc.io/doc/com.babelqueue/babelqueue-core)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

> **Polyglot Queues, Simplified.** Read and write the canonical BabelQueue message
> envelope from Java — so your Java/Spring services exchange messages with Laravel,
> Symfony, Python, Go and Node over one strict JSON format, on the broker you
> already run.

This is the framework-agnostic **Java core**: the wire-envelope codec, contracts
and dead-letter helpers — **zero dependencies** (pure JDK, including its own
minimal JSON codec, so no Jackson/Gson is forced on you). The full standard is
documented at **[babelqueue.com](https://babelqueue.com)**.

## Installation

Maven:

```xml
<dependency>
    <groupId>com.babelqueue</groupId>
    <artifactId>babelqueue-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.babelqueue:babelqueue-core:1.0.0")
```

Requires Java **17+**.

## Usage

```java
import com.babelqueue.*;
import java.util.Map;

// Produce — build the canonical envelope and publish the JSON to your broker.
Envelope env = EnvelopeCodec.make(
    "urn:babel:orders:created",
    Map.of("order_id", 1042L),
    "orders",
    null);
String body = EnvelopeCodec.encode(env); // compact UTF-8 JSON
// jedis.rpush("queues:orders", body);
//   /  channel.basicPublish("", "orders", props, body.getBytes(StandardCharsets.UTF_8));

// Consume — decode a message produced by ANY BabelQueue SDK.
Envelope in = EnvelopeCodec.decode(body);
if (EnvelopeCodec.accepts(in)) {
    switch (EnvelopeCodec.urn(in)) {
        case "urn:babel:orders:created" ->
            System.out.println(in.data().get("order_id") + " " + in.traceId());
        default -> { /* unknown URN */ }
    }
}
```

The envelope is identical to every other SDK's:

```json
{
  "job": "urn:babel:orders:created",
  "trace_id": "…",
  "data": { "order_id": 1042 },
  "meta": { "id": "…", "queue": "orders", "lang": "java", "schema_version": 1, "created_at": 1749132727000 },
  "attempts": 0
}
```

> JSON numbers decode into `data` as `Long` (integers) or `Double` (decimals);
> objects as `LinkedHashMap` (insertion order preserved). `encode` leaves slashes
> and non-ASCII unescaped, so the bytes match the PHP/Python/Node cores.

### Typed messages (optional)

```java
record OrderCreated(long orderId) implements PolyglotMessage, HasTraceId {
    public String getBabelUrn() { return "urn:babel:orders:created"; }
    public Map<String, Object> toPayload() { return Map.of("order_id", orderId); }
    public String getBabelTraceId() { return null; } // or an inbound trace to continue
}

Envelope env = EnvelopeCodec.fromMessage(new OrderCreated(1042L), "orders");
```

### Dead-letter

```java
Envelope dlq = DeadLetters.annotate(env, "failed", "orders", 3, "boom", "java.lang.RuntimeException");
// publish EnvelopeCodec.encode(dlq) to the "orders.dlq" queue
```

`DeadLetters.annotate` returns a copy — the original envelope is preserved
unchanged inside the dead-lettered message, so any-language consumers can still
read it.

## What this core is (and isn't)

It enforces the **contract**: the envelope shape, URN identity, trace propagation,
schema-version gating and the dead-letter block. It is intentionally **not** a
worker/runtime — broker wiring, acks and retry loops stay in your own code (or a
future Spring adapter), exactly as with the other SDK cores.

`UnknownUrnStrategy` (`FAIL`, `DELETE`, `RELEASE`, `DEAD_LETTER`) is provided for
adapters to act on.

## Conformance

This core passes the shared **cross-SDK conformance suite** (vendored under
[`src/test/resources/conformance/`](src/test/resources/conformance)) — the same
fixtures every BabelQueue SDK must satisfy, so a Java producer and, say, a Laravel
consumer agree byte-for-byte.

```bash
mvn test
```

## License

[MIT](LICENSE) © Muhammet Şafak
