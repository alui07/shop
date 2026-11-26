package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.shop.api.support.MutableClock;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.BucketExceptions.BucketExecutionException;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class RateLimiterTest {

    private static final long VERSION = 42;

    @SuppressWarnings("unchecked")
    private final ProxyManager<String> proxies = mock(ProxyManager.class);

    @SuppressWarnings("unchecked")
    private final RemoteBucketBuilder<String> builder = mock(RemoteBucketBuilder.class);

    private final BucketProxy bucket = mock(BucketProxy.class);
    private final LimiterBackend backend = mock(LimiterBackend.class);
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RateLimiter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void wire() {
        when(backend.proxies()).thenReturn(proxies);
        when(proxies.builder()).thenReturn(builder);
        when(builder.withImplicitConfigurationReplacement(anyLong(), any())).thenReturn(builder);
        when(builder.build(any(String.class), any(BucketConfiguration.class))).thenReturn(bucket);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void unwire() {
        logger.detachAppender(logs);
    }

    private RateLimiter limiter(int scale) {
        return new RateLimiter(backend, clock, scale, VERSION, meters);
    }

    private double rejected(String rule) {
        return meters.get(RateLimiter.REJECTED_METER)
                .tag("rule", rule)
                .counter()
                .count();
    }

    private double failures() {
        return meters.get(RateLimiter.BACKEND_FAILURES_METER).counter().count();
    }

    private List<ILoggingEvent> logged(Level level) {
        return logs.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    @Test
    void aConsumedTokenIsAllowedAndTheBucketKeyHoldsOnlyTheRuleAndTheSubject() {
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(ConsumptionProbe.consumed(9, 0));

        RateLimiter.Decision decision = limiter(1).tryConsume(RateLimitRule.CHECKOUT, "sub-1");

        assertThat(decision.allowed()).isTrue();
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(builder).build(key.capture(), any(BucketConfiguration.class));
        assertThat(key.getValue()).isEqualTo("shop:rate:checkout:sub-1");
        verify(builder).withImplicitConfigurationReplacement(VERSION, TokensInheritanceStrategy.PROPORTIONALLY);
        assertThat(rejected("checkout")).isZero();
    }

    @Test
    void aRejectionCarriesTheWaitRoundedUpToWholeSecondsWithAMinimumOfOne() {
        long second = 1_000_000_000L;
        List<long[]> cases = List.of(
                new long[] {1, 1}, new long[] {0, 1}, new long[] {second, 1}, new long[] {second + 1, 2}, new long[] {
                    2 * second, 2
                });
        for (long[] waitAndExpected : cases) {
            when(bucket.tryConsumeAndReturnRemaining(1))
                    .thenReturn(ConsumptionProbe.rejected(0, waitAndExpected[0], waitAndExpected[0]));

            RateLimiter.Decision decision = limiter(1).tryConsume(RateLimitRule.CART_WRITES, "sub-1");

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.retryAfterSeconds())
                    .as("wait of %d ns", waitAndExpected[0])
                    .isEqualTo(waitAndExpected[1]);
        }
    }

    @Test
    void everyRejectionIsCountedButOnlyTheFirstPerRulePerMinuteIsLogged() {
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(ConsumptionProbe.rejected(0, 1, 1));
        RateLimiter limiter = limiter(1);

        limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1");
        limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-2");
        limiter.tryConsume(RateLimitRule.ADMIN_WRITES, "sub-1");

        assertThat(rejected("checkout")).isEqualTo(2);
        assertThat(rejected("admin-writes")).isEqualTo(1);
        assertThat(logged(Level.INFO)).hasSize(2);
        assertThat(logged(Level.INFO).get(0).getFormattedMessage())
                .contains("checkout")
                .contains("/api/checkout")
                .doesNotContain("sub-");

        clock.advance(Duration.ofMinutes(1));
        limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1");

        assertThat(logged(Level.INFO)).hasSize(3);
    }

    @Test
    void theScaleMultipliesEveryCapacityAndItsRefill() {
        BucketConfiguration scaled = limiter(1000).configuration(RateLimitRule.CHECKOUT);
        BucketConfiguration plain = limiter(1).configuration(RateLimitRule.CHECKOUT);

        assertThat(capacities(plain)).containsExactly(30L, 100L);
        assertThat(capacities(scaled)).containsExactly(30_000L, 100_000L);
        assertThat(scaled.getBandwidths()[0].getRefillTokens()).isEqualTo(30_000L);
        assertThat(scaled.getBandwidths()[0].getRefillPeriodNanos())
                .isEqualTo(Duration.ofMinutes(1).toNanos());
        assertThat(scaled.getBandwidths()[1].getRefillPeriodNanos())
                .isEqualTo(Duration.ofHours(1).toNanos());
    }

    private static List<Long> capacities(BucketConfiguration configuration) {
        return java.util.Arrays.stream(configuration.getBandwidths())
                .map(Bandwidth::getCapacity)
                .toList();
    }

    @Test
    void aFailureOfTheStoreAllowsTheRequestCountsItAndWarnsOncePerMinute() {
        when(bucket.tryConsumeAndReturnRemaining(1)).thenThrow(new RedisCommandTimeoutException("slow"));
        RateLimiter limiter = limiter(1);

        assertThat(limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1").allowed())
                .isTrue();
        assertThat(limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1").allowed())
                .isTrue();

        assertThat(failures()).isEqualTo(2);
        assertThat(logged(Level.WARN)).hasSize(1);
        assertThat(logged(Level.WARN).get(0).getFormattedMessage()).contains("RedisCommandTimeoutException");

        clock.advance(Duration.ofMinutes(1));
        limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1");

        assertThat(logged(Level.WARN)).hasSize(2);
    }

    @Test
    void everyKindOfStoreFailureFailsOpenEvenWhenWrapped() {
        List<RuntimeException> failures = List.of(
                new RedisException("down"),
                new RedisConnectionException("refused"),
                new BucketExecutionException("lost"),
                new CompletionException(new RedisException("wrapped")),
                new RuntimeException("outer", new TimeoutException("request timeout")));
        RateLimiter limiter = limiter(1);

        for (RuntimeException failure : failures) {
            doThrow(failure).when(bucket).tryConsumeAndReturnRemaining(1);
            assertThat(limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1").allowed())
                    .as(failure.toString())
                    .isTrue();
        }

        assertThat(failures()).isEqualTo(failures.size());
    }

    @Test
    void aFailureToConnectFailsOpenToo() {
        when(backend.proxies()).thenThrow(new RedisConnectionException("refused"));

        assertThat(limiter(1).tryConsume(RateLimitRule.CHECKOUT, "sub-1").allowed())
                .isTrue();

        assertThat(failures()).isEqualTo(1);
    }

    @Test
    void anythingThatIsNotTheStoreFailingPropagates() {
        when(bucket.tryConsumeAndReturnRemaining(1)).thenThrow(new IllegalStateException("bug"));
        RateLimiter limiter = limiter(1);

        assertThatThrownBy(() -> limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1"))
                .isInstanceOf(IllegalStateException.class);
        doThrow(new CompletionException(new IllegalArgumentException("wrapped bug")))
                .when(bucket)
                .tryConsumeAndReturnRemaining(1);
        assertThatThrownBy(() -> limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1"))
                .isInstanceOf(CompletionException.class);
        doThrow(new NoSuchMethodError("binary incompatibility")).when(bucket).tryConsumeAndReturnRemaining(1);
        assertThatThrownBy(() -> limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1"))
                .isInstanceOf(LinkageError.class);

        assertThat(failures()).isZero();
    }
}
