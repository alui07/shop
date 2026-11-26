package dev.shop.api.auth;

import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfiguration {

    @Bean(destroyMethod = "destroy")
    LimiterBackend limiterBackend(DataRedisConnectionDetails details, ClientResources resources, Clock clock) {
        return new LettuceLimiterBackend(details, resources, clock);
    }

    @Bean
    OpenApiCustomizer rateLimitedResponseCustomizer() {
        return new RateLimitedResponseCustomizer();
    }

    @Bean
    RateLimiter rateLimiter(LimiterBackend backend, Clock clock, RateLimitProperties properties, MeterRegistry meters) {
        return new RateLimiter(backend, clock, properties.scale(), clock.millis(), meters);
    }
}
