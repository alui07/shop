package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OncePerMinuteTest {

    private final MutableClock clock = new MutableClock();
    private final OncePerMinute once = new OncePerMinute(clock);

    @Test
    void theFirstEventPerKeyPassesAndTheRestOfTheMinuteDoesNot() {
        assertThat(once.tryAcquire("a")).isTrue();
        assertThat(once.tryAcquire("a")).isFalse();
        assertThat(once.tryAcquire("b")).as("another key has its own minute").isTrue();

        clock.advance(Duration.ofSeconds(59));
        assertThat(once.tryAcquire("a")).isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(once.tryAcquire("a")).isTrue();
        assertThat(once.tryAcquire("a")).isFalse();
    }
}
