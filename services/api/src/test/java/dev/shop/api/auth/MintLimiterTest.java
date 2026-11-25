package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MintLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final MintLimiter limiter = new MintLimiter(clock, 1);

    @Test
    void grantsTheLimitPerSecondThenRefusesUntilTheNextWindow() {
        clock.advance(Duration.ofSeconds(10));
        boolean first = limiter.tryAcquire();
        boolean secondInSameWindow = limiter.tryAcquire() && limiter.tryAcquire();
        clock.advance(Duration.ofSeconds(2));

        assertThat(first).isTrue();
        assertThat(secondInSameWindow).isFalse();
        assertThat(limiter.tryAcquire()).isTrue();
    }
}
