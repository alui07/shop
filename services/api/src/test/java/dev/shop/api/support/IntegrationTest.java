package dev.shop.api.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * One shared definition keeps the integration tests on a single cached application context. Rate limiting is off here
 * because many tests act as one account in quick succession; only RateLimitIT turns it on.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "springdoc.api-docs.enabled=true",
            "shop.jobs.enabled=false",
            "shop.payment.provider=fake",
            "spring.cache.type=none",
            "shop.rate-limit.enabled=false"
        })
@TestInfrastructure
public @interface IntegrationTest {}
