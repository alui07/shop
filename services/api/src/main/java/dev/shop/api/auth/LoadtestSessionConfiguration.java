package dev.shop.api.auth;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Only for the load test: mints customer sessions without Keycloak. The chain owns exactly {@code POST} on the path;
 * the controller authenticates the token header, which a cross-site page cannot set, so CSRF is off here.
 */
@Configuration(proxyBeanMethods = false)
@Profile("loadtest")
@EnableConfigurationProperties(LoadtestProperties.class)
class LoadtestSessionConfiguration {

    static final String MINT_PATH = "/api/loadtest/sessions";

    @Bean
    @Order(2)
    SecurityFilterChain loadtestSecurityFilterChain(HttpSecurity http, ProblemResponses problems) throws Exception {
        http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, MINT_PATH))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        return http.build();
    }

    @Bean
    MintLimiter mintLimiter(Clock clock, LoadtestProperties properties) {
        return new MintLimiter(clock, properties.maxMintsPerSecond());
    }
}
