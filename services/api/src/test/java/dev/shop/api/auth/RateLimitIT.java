package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.TestBrowser;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class RateLimitIT extends RateLimitITSupport {

    private static final long CHECKOUT_PER_MINUTE =
            RateLimitRule.CHECKOUT.limits().get(0).capacity();
    private static final long ADMIN_WRITES_PER_MINUTE =
            RateLimitRule.ADMIN_WRITES.limits().get(0).capacity();

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ClientResources clientResources;

    @Autowired
    private Clock applicationClock;

    @Test
    void theCheckoutAfterTheMinuteCapacityIsRejectedWithAProblemRetryAfterAndTheSecurityHeaders() {
        AdminClient client = signedInCustomer(newSubject());
        for (long i = 0; i < CHECKOUT_PER_MINUTE; i++) {
            assertThat(checkout(client).statusCode()).as("request %d", i + 1).isNotEqualTo(429);
        }
        double rejectedBefore = rejectedCheckouts();

        HttpResponse<String> rejected = checkout(client);

        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode problem = AdminClient.json(rejected);
        assertThat(problem.get("type").asString()).isEqualTo("tag:shop.dev,2026:rate-limited");
        assertThat(problem.get("status").asInt()).isEqualTo(429);
        assertThat(problem.get("detail").asString()).isEqualTo("Too many requests; try again later");
        assertThat(problem.get("errors")).isEmpty();
        long retryAfter =
                Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(rejected.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(rejected.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(rejectedCheckouts()).isEqualTo(rejectedBefore + 1);
    }

    @Test
    void anotherAccountIsUnaffectedAndTheBucketRefillsWhenTheClockMoves() {
        AdminClient first = signedInCustomer(newSubject());
        AdminClient second = signedInCustomer(newSubject());
        for (long i = 0; i < CHECKOUT_PER_MINUTE; i++) {
            checkout(first);
        }

        assertThat(checkout(first).statusCode()).isEqualTo(429);
        assertThat(checkout(second).statusCode()).isNotEqualTo(429);

        clock.advance(Duration.ofMinutes(1));

        assertThat(checkout(first).statusCode()).isNotEqualTo(429);
    }

    @Test
    void theHourlyBandwidthAppliesEvenWhenTheMinuteBandwidthNeverDoes() {
        String subject = newSubject();
        int allowed = 0;
        try (var pod = pod(1, 1_000)) {
            while (allowed < 200
                    && pod.limiter.tryConsume(RateLimitRule.CHECKOUT, subject).allowed()) {
                allowed++;
                clock.advance(Duration.ofSeconds(6));
            }
        }

        assertThat(allowed)
                .as("a request every 6 s fits the minute bandwidth; 100 an hour runs out near 120")
                .isBetween(118, 122);
    }

    @Test
    void anAnonymousCallerGetsA401AndCreatesNoKey() {
        TestBrowser anonymous = browser();
        anonymous.get("/api/me");

        HttpResponse<String> response = anonymous.postWithCsrf(
                "/api/checkout", CHECKOUT_BODY, "Content-Type", "application/json", "Idempotency-Key", "k");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(limiterKeys()).isEmpty();
    }

    @Test
    void refusedRequestsCreateNoKey() {
        AdminClient customer = signedInCustomer(newSubject());

        HttpResponse<String> withoutCsrf =
                customer.postWithoutCsrf("/api/checkout", CHECKOUT_BODY, "Idempotency-Key", "k");
        HttpResponse<String> notAnAdmin = customer.post("/api/admin/products", "{}");

        assertThat(withoutCsrf.statusCode()).isEqualTo(403);
        assertThat(notAnAdmin.statusCode()).isEqualTo(403);
        assertThat(limiterKeys()).isEmpty();
    }

    @Test
    void aKeyHoldsOnlyTheRuleAndTheSubjectAndExpires() {
        String subject = newSubject();

        checkout(signedInCustomer(subject));

        assertThat(limiterKeys()).containsExactly("shop:rate:checkout:" + subject);
        long ttl = limiterStore().pttl("shop:rate:checkout:" + subject);
        assertThat(ttl)
                .as("every key expires, so it stays evictable")
                .isPositive()
                .isLessThan(3_700_000);
    }

    @Test
    void everyRuleCountsItsOwnRoutes() {
        AdminClient customer = signedInCustomer(newSubject());
        AdminClient admin = signedInAdmin();

        customer.put("/api/cart/lines/" + LAMP, "{\"quantity\":1}");
        customer.post("/api/orders/" + UUID.randomUUID() + "/cancel", "");
        customer.post("/api/orders/" + UUID.randomUUID() + "/payment", "");
        admin.post("/api/admin/products", "{}");

        assertThat(limiterKeys())
                .extracting(key -> key.split(":")[2])
                .containsExactlyInAnyOrder("cart-writes", "order-actions", "admin-writes");
    }

    @Test
    void readsAreNeverCounted() {
        AdminClient customer = signedInCustomer(newSubject());

        customer.get("/api/cart");
        customer.get("/api/orders");

        assertThat(limiterKeys()).isEmpty();
    }

    @Test
    void twoPodsOnTheSameValkeyShareOneBucket() {
        String subject = newSubject();
        try (var podA = pod(1, 1_000);
                var podB = pod(1, 1_000)) {
            for (long i = 0; i < CHECKOUT_PER_MINUTE / 2; i++) {
                assertThat(podA.limiter
                                .tryConsume(RateLimitRule.CHECKOUT, subject)
                                .allowed())
                        .isTrue();
                assertThat(podB.limiter
                                .tryConsume(RateLimitRule.CHECKOUT, subject)
                                .allowed())
                        .isTrue();
            }

            assertThat(podA.limiter.tryConsume(RateLimitRule.CHECKOUT, subject).allowed())
                    .isFalse();
            assertThat(podB.limiter.tryConsume(RateLimitRule.CHECKOUT, subject).allowed())
                    .isFalse();
        }
    }

    @Test
    void aBucketCreatedAtTheOldCapacityFollowsTheNewConfiguration() {
        String subject = newSubject();
        try (var oldPod = pod(1, 1_000);
                var newPod = pod(2, 1_001)) {
            assertThat(oldPod.limiter
                            .tryConsume(RateLimitRule.ADMIN_WRITES, subject)
                            .allowed())
                    .isTrue();

            int allowedAfterTheChange = 0;
            while (newPod.limiter
                            .tryConsume(RateLimitRule.ADMIN_WRITES, subject)
                            .allowed()
                    && allowedAfterTheChange < ADMIN_WRITES_PER_MINUTE * 4) {
                allowedAfterTheChange++;
            }

            assertThat(allowedAfterTheChange)
                    .as("the bucket follows the doubled capacity and is not stuck at the old one")
                    .isBetween((int) ADMIN_WRITES_PER_MINUTE * 2 - 2, (int) ADMIN_WRITES_PER_MINUTE * 2);
        }
    }

    private double rejectedCheckouts() {
        return meters.get(RateLimiter.REJECTED_METER)
                .tag("rule", "checkout")
                .counter()
                .count();
    }

    private Pod pod(int scale, long configurationVersion) {
        LettuceLimiterBackend backend = new LettuceLimiterBackend(
                RateLimitTestConfiguration.detailsOf(limiterValkey), clientResources, applicationClock);
        return new Pod(backend, new RateLimiter(backend, applicationClock, scale, configurationVersion, meters));
    }

    private record Pod(LettuceLimiterBackend backend, RateLimiter limiter) implements AutoCloseable {
        @Override
        public void close() {
            backend.destroy();
        }
    }
}
