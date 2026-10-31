package com.babelqueue.otel;

import com.babelqueue.DeadLetterEvent;
import com.babelqueue.DeadLetterObserver;
import com.babelqueue.EnvelopeCodec;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.List;
import java.util.Objects;

/**
 * The standard BabelQueue metric set (ADR-0039), recorded through an OpenTelemetry {@link Meter}
 * the caller supplies. Metrics are opt-in: with no {@code Metrics} (no meter) the producer and
 * consumer wrappers record nothing.
 *
 * <table>
 *   <caption>Instruments</caption>
 *   <tr><th>Instrument</th><th>Kind</th><th>Unit</th><th>Recorded by</th></tr>
 *   <tr><td>{@value #PROCESS_DURATION}</td><td>histogram</td><td>{@code s}</td>
 *       <td>consumer wrapper, once per handler invocation</td></tr>
 *   <tr><td>{@value #SENT_MESSAGES}</td><td>counter</td><td>{@code {message}}</td>
 *       <td>producer wrapper, per publish attempt</td></tr>
 *   <tr><td>{@value #CONSUMED_MESSAGES}</td><td>counter</td><td>{@code {message}}</td>
 *       <td>consumer wrapper, per delivery</td></tr>
 *   <tr><td>{@value #RETRIES}</td><td>counter</td><td>{@code {message}}</td>
 *       <td>consumer wrapper, per delivery with {@code attempts > 0}</td></tr>
 *   <tr><td>{@value #DLQ_MESSAGES}</td><td>counter</td><td>{@code {message}}</td>
 *       <td>{@link #deadLetterObserver(String) dead-letter observer}</td></tr>
 * </table>
 *
 * <p>Every data point carries exactly {@code messaging.system}, {@code messaging.operation.name},
 * {@code messaging.destination.name} (when the destination is known) and, on a failed operation,
 * {@code error.type}. {@code babelqueue.urn}, message ids, {@code trace_id}, attempts, the schema
 * version, the consumer group and the dead-letter reason are never metric attributes (cardinality
 * limit, ADR-0039 section 2).
 *
 * <p>An instance creates its instruments once; build it once and reuse it (for example through
 * {@link Tracing.Options#meter(Meter)}, which does exactly that).
 */
public final class Metrics {

    /** Histogram, seconds: the handler's wall time. */
    public static final String PROCESS_DURATION = "messaging.process.duration";
    /** Counter: publish attempts. */
    public static final String SENT_MESSAGES = "messaging.client.sent.messages";
    /** Counter: deliveries that entered the consumer wrapper. */
    public static final String CONSUMED_MESSAGES = "messaging.client.consumed.messages";
    /** Counter: deliveries with top-level {@code attempts > 0}. */
    public static final String RETRIES = "babelqueue.retries";
    /** Counter: dead-letters the SDK completed. */
    public static final String DLQ_MESSAGES = "babelqueue.dlq.messages";

    /** The semconv advisory bucket boundaries for {@link #PROCESS_DURATION}, in seconds. */
    static final List<Double> DURATION_BUCKETS =
        List.of(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0);

    private static final String UNIT_MESSAGE = "{message}";

    private static final AttributeKey<String> SYSTEM = AttributeKey.stringKey("messaging.system");
    private static final AttributeKey<String> OPERATION_NAME = AttributeKey.stringKey("messaging.operation.name");
    private static final AttributeKey<String> DESTINATION = AttributeKey.stringKey("messaging.destination.name");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    private final DoubleHistogram processDuration;
    private final LongCounter sent;
    private final LongCounter consumed;
    private final LongCounter retries;
    private final LongCounter dlq;

    private Metrics(Meter meter) {
        this.processDuration = meter.histogramBuilder(PROCESS_DURATION)
            .setUnit("s")
            .setDescription("Duration of processing operations.")
            .setExplicitBucketBoundariesAdvice(DURATION_BUCKETS)
            .build();
        this.sent = meter.counterBuilder(SENT_MESSAGES)
            .setUnit(UNIT_MESSAGE)
            .setDescription("Number of messages producer attempted to send to the broker.")
            .build();
        this.consumed = meter.counterBuilder(CONSUMED_MESSAGES)
            .setUnit(UNIT_MESSAGE)
            .setDescription("Number of messages that were delivered to the application.")
            .build();
        this.retries = meter.counterBuilder(RETRIES)
            .setUnit(UNIT_MESSAGE)
            .setDescription("Number of deliveries that entered the consumer with attempts > 0.")
            .build();
        this.dlq = meter.counterBuilder(DLQ_MESSAGES)
            .setUnit(UNIT_MESSAGE)
            .setDescription("Number of dead-letters the SDK wrote or initiated.")
            .build();
    }

    /**
     * Creates the five instruments on {@code meter}.
     *
     * @param meter the OpenTelemetry meter (create it with {@link Tracing#SCHEMA_URL} to pin the
     *              semantic-conventions release)
     * @return the metric set
     */
    public static Metrics create(Meter meter) {
        return new Metrics(Objects.requireNonNull(meter, "meter"));
    }

    /**
     * A {@link DeadLetterObserver} that increments {@value #DLQ_MESSAGES} with
     * {@code messaging.system}, {@code messaging.destination.name = event.queue} (omitted when the
     * queue is empty) and {@code messaging.operation.name = process}. Hand it to a transport
     * consumer that accepts a dead-letter observer.
     *
     * @param system the {@code messaging.system} value; {@code null} or empty means
     *               {@value Tracing#DEFAULT_SYSTEM}
     * @return the observer
     */
    public DeadLetterObserver deadLetterObserver(String system) {
        String resolved = Tracing.resolveSystem(system);
        return (DeadLetterEvent event) -> dlq.add(1, attributes(resolved, "process", event.queue(), null));
    }

    void recordSent(String system, String destination, String errorType) {
        sent.add(1, attributes(system, "send", destination, errorType));
    }

    void recordConsumed(String system, String destination, int attempts, double seconds, String errorType) {
        Attributes plain = attributes(system, "process", destination, null);
        consumed.add(1, plain);
        if (attempts > 0) {
            retries.add(1, plain);
        }
        processDuration.record(seconds, attributes(system, "process", destination, errorType));
    }

    private static Attributes attributes(String system, String operation, String destination, String errorType) {
        AttributesBuilder b = Attributes.builder()
            .put(SYSTEM, system)
            .put(OPERATION_NAME, operation);
        if (!EnvelopeCodec.isBlankAscii(destination)) {
            b.put(DESTINATION, destination);
        }
        if (errorType != null) {
            b.put(ERROR_TYPE, errorType);
        }
        return b.build();
    }
}
