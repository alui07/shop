package dev.shop.api.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.DoubleSupplier;

/** A gauge value read at most once per export step, so scrapes and exports never query more often than that. */
final class StepCachedReading {

    private final Clock clock;
    private final Duration step;
    private final DoubleSupplier source;
    private Instant readAt;
    private double value;

    StepCachedReading(Clock clock, Duration step, DoubleSupplier source) {
        this.clock = clock;
        this.step = step;
        this.source = source;
    }

    synchronized double value() {
        Instant now = clock.instant();
        if (readAt == null || !readAt.plus(step).isAfter(now)) {
            value = source.getAsDouble();
            readAt = now;
        }
        return value;
    }
}
