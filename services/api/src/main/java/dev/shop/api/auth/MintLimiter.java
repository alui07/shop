package dev.shop.api.auth;

import java.time.Clock;

/** A fixed one-second window: at most {@code limit} permits per window on this pod. */
final class MintLimiter {

    private final Clock clock;
    private final int limit;
    private long windowSecond = Long.MIN_VALUE;
    private int used;

    MintLimiter(Clock clock, int limit) {
        this.clock = clock;
        this.limit = limit;
    }

    synchronized boolean tryAcquire() {
        long second = clock.instant().getEpochSecond();
        if (second != windowSecond) {
            windowSecond = second;
            used = 0;
        }
        if (used >= limit) {
            return false;
        }
        used++;
        return true;
    }
}
