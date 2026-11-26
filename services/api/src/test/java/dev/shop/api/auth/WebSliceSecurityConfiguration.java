package dev.shop.api.auth;

import static org.mockito.Mockito.mock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.oidc.session.InMemoryOidcSessionRegistry;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.session.SessionRepository;

@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfiguration.class, AuthClientConfiguration.class})
public class WebSliceSecurityConfiguration {

    @Bean
    OidcSessionRegistry sessionRegistry() {
        return new InMemoryOidcSessionRegistry();
    }

    @Bean
    SessionRepository<?> sessionRepository() {
        return mock(SessionRepository.class);
    }

    @Bean
    RateLimiter rateLimiter() {
        return mock(RateLimiter.class);
    }

    @Bean
    RateLimitProperties rateLimitProperties() {
        return new RateLimitProperties(false, 1);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate() {
        return mock(StringRedisTemplate.class);
    }
}
