package dev.shop.api.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.BucketExceptions.BucketExecutionException;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes one token per request from the account's bucket for a rule. When the bucket store fails the request is allowed,
 * that request only: a Valkey outage must not become a write outage, and Envoy's per-address limits still apply. The
 * limiter is never switched off for later requests here; the backend alone decides to skip a store it cannot connect to.
 */
final class RateLimiter {

    static final String KEY_PREFIX = "shop:rate:";
    static final String REJECTED_METER = "shop.rate_limit.rejected";
    static final String BACKEND_FAILURES_METER = "shop.rate_limit.backend.failures";

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimiter.class);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private final LimiterBackend backend;
    private final int scale;
    private final long configurationVersion;
    private final OncePerMinute logThrottle;
    private final Map<RateLimitRule, Counter> rejections = new EnumMap<>(RateLimitRule.class);
    private final Counter backendFailures;

    /**
     * Bucket4j replaces a stored configuration only when the version offered is higher than the stored one, so the
     * version must grow with every configuration change: a hash of the values would not. The start time of the pod
     * does, and pods with the same values replace a bucket with an equal one, which changes nothing.
     */
    RateLimiter(LimiterBackend backend, Clock clock, int scale, long configurationVersion, MeterRegistry meters) {
        this.backend = backend;
        this.scale = scale;
        this.configurationVersion = configurationVersion;
        this.logThrottle = new OncePerMinute(clock);
        for (RateLimitRule rule : RateLimitRule.values()) {
            rejections.put(
                    rule,
                    Counter.builder(REJECTED_METER).tag("rule", rule.ruleName()).register(meters));
        }
        this.backendFailures = Counter.builder(BACKEND_FAILURES_METER)
                .description("Requests let through unlimited because the rate limit store failed")
                .register(meters);
    }

    Decision tryConsume(RateLimitRule rule, String subject) {
        try {
            ConsumptionProbe probe = backend.proxies()
                    .builder()
                    .withImplicitConfigurationReplacement(
                            configurationVersion, TokensInheritanceStrategy.PROPORTIONALLY)
                    .build(KEY_PREFIX + rule.ruleName() + ":" + subject, configuration(rule))
                    .tryConsumeAndReturnRemaining(1);
            if (probe.isConsumed()) {
                return Decision.ALLOWED;
            }
            return rejected(rule, probe.getNanosToWaitForRefill());
        } catch (RuntimeException e) {
            if (!isBackendFailure(e)) {
                throw e;
            }
            return failOpen(e);
        }
    }

    BucketConfiguration configuration(RateLimitRule rule) {
        var builder = BucketConfiguration.builder();
        for (RateLimitRule.Limit limit : rule.limits()) {
            long capacity = limit.capacity() * scale;
            builder.addLimit(Bandwidth.builder()
                    .capacity(capacity)
                    .refillGreedy(capacity, limit.period())
                    .build());
        }
        return builder.build();
    }

    private Decision rejected(RateLimitRule rule, long nanosToWait) {
        rejections.get(rule).increment();
        if (logThrottle.tryAcquire("rejected:" + rule.ruleName())) {
            LOGGER.info(
                    "Rate limit {} exceeded on {}; further rejections this minute are only counted",
                    rule.ruleName(),
                    rule.describe());
        }
        long seconds = Math.max(1, Math.ceilDiv(nanosToWait, NANOS_PER_SECOND));
        return new Decision(false, seconds);
    }

    private Decision failOpen(RuntimeException failure) {
        backendFailures.increment();
        if (logThrottle.tryAcquire("backend")) {
            LOGGER.warn(
                    "The rate limit store is unavailable; requests are allowed without limits: {}",
                    failure.getClass().getSimpleName());
        }
        return Decision.ALLOWED;
    }

    /** Only the store failing opens the limiter; a bug or a classpath mismatch must surface. */
    private static boolean isBackendFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisException
                    || cause instanceof BucketExecutionException
                    || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
