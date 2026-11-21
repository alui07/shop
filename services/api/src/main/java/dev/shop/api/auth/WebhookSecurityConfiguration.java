package dev.shop.api.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * The payment provider's webhook: authenticated by the HMAC signature the controller checks, so no session, no login
 * and no CSRF token. Session management and anonymous authentication are off too: both read the session id, which
 * would touch the session store.
 * Only the exact path and method belong to this chain; every other request, including variants of
 * the path, falls to the main chain, which denies them.
 */
@Configuration(proxyBeanMethods = false)
class WebhookSecurityConfiguration {

    static final String WEBHOOK_PATH = "/api/payments/webhook";

    @Bean
    @Order(1)
    SecurityFilterChain webhookSecurityFilterChain(HttpSecurity http, ProblemResponses problems) throws Exception {
        http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, WEBHOOK_PATH))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .sessionManagement(session -> session.disable())
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(csrf -> csrf.disable())
                .anonymous(anonymous -> anonymous.disable())
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        return http.build();
    }
}
