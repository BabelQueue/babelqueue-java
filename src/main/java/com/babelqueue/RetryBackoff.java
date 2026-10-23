package com.babelqueue;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Worker-side retry backoff (ADR-0034 section 9). Pure arithmetic with no broker and no
 * dependencies: transports ask it how long to delay the next attempt.
 *
 * <p>{@code d(n) = min(max, base * multiplier^(n-1))} seconds, where {@code n = attempts + 1} is
 * 1-based and {@code max == 0} means no cap. With {@link Jitter#FULL} the delay is drawn from
 * {@code uniform(0, d(n))}. The defaults ({@code base 0}, {@code multiplier 1}, {@code max 0},
 * {@code Jitter.NONE}) always yield {@code 0}, so a helper nobody configured keeps the previous
 * behaviour. Kafka does not apply this at all: its retry tiers are the delay.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class RetryBackoff {

    /** Jitter strategy. */
    public enum Jitter {
        /** The delay is exactly {@code d(n)}. */
        NONE,
        /** The delay is {@code uniform(0, d(n))}. */
        FULL;

        /** Parses the {@code retry_jitter} parameter value ({@code none} or {@code full}). */
        public static Jitter parse(String value) {
            if (value == null) {
                throw new BabelQueueException("retry_jitter must be 'none' or 'full'");
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "none" -> NONE;
                case "full" -> FULL;
                default -> throw new BabelQueueException("retry_jitter must be 'none' or 'full', got '" + value + "'");
            };
        }
    }

    private static final RetryBackoff NONE_CONFIGURED = new RetryBackoff(0, 1, 0, Jitter.NONE, null);

    private final double base;
    private final double multiplier;
    private final double max;
    private final Jitter jitter;
    private final DoubleSupplier random;

    private RetryBackoff(double base, double multiplier, double max, Jitter jitter, DoubleSupplier random) {
        this.base = base;
        this.multiplier = multiplier;
        this.max = max;
        this.jitter = jitter;
        this.random = random;
    }

    /** The default helper: {@code base 0}, {@code multiplier 1}, {@code max 0}, no jitter; always 0 s. */
    public static RetryBackoff none() {
        return NONE_CONFIGURED;
    }

    /**
     * @param base       {@code retry_backoff}, seconds, {@code >= 0}
     * @param multiplier {@code retry_backoff_multiplier}, {@code >= 1}
     * @param max        {@code retry_backoff_max}, seconds, {@code >= 0}; {@code 0} means unlimited
     * @param jitter     {@code retry_jitter}
     */
    public static RetryBackoff of(double base, double multiplier, double max, Jitter jitter) {
        return of(base, multiplier, max, jitter, null);
    }

    /** As {@link #of(double, double, double, Jitter)} with a caller-provided {@code [0,1)} source (tests). */
    public static RetryBackoff of(double base, double multiplier, double max, Jitter jitter, DoubleSupplier random) {
        if (!(base >= 0) || Double.isInfinite(base)) {
            throw new BabelQueueException("retry_backoff must be a finite number >= 0");
        }
        if (!(multiplier >= 1) || Double.isInfinite(multiplier)) {
            throw new BabelQueueException("retry_backoff_multiplier must be a finite number >= 1");
        }
        if (!(max >= 0) || Double.isInfinite(max)) {
            throw new BabelQueueException("retry_backoff_max must be a finite number >= 0");
        }
        if (jitter == null) {
            throw new BabelQueueException("retry_jitter must not be null");
        }
        return new RetryBackoff(base, multiplier, max, jitter, random);
    }

    /** The deterministic ceiling {@code d(n)} in seconds, {@code n >= 1} (values below 1 count as 1). */
    public double ceilingSeconds(int n) {
        if (base == 0) {
            // 0 * multiplier^(n-1) is 0 for every n; skip pow() so an overflowing exponent (0 * Infinity = NaN)
            // cannot lift a zero base up to the cap.
            return 0.0;
        }
        int exponent = Math.max(n, 1) - 1;
        double d = base * Math.pow(multiplier, exponent);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            d = max > 0 ? max : Double.MAX_VALUE;
        }
        return max > 0 ? Math.min(max, d) : d;
    }

    /**
     * The delay in seconds before attempt {@code n} ({@code n = attempts + 1}, 1-based). Fractional;
     * transports round up where the broker needs whole seconds.
     */
    public double delaySeconds(int n) {
        double d = ceilingSeconds(n);
        if (jitter == Jitter.NONE || d == 0) {
            return d;
        }
        double u = random != null ? random.getAsDouble() : ThreadLocalRandom.current().nextDouble();
        return d * u;
    }

    /** Whether {@code retry_jitter} is {@code full}. */
    public Jitter jitter() {
        return jitter;
    }
}
