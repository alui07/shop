package dev.shop.api.auth;

import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration {

    static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ShopAuthProperties properties,
            ClientRegistrationRepository registrations,
            OidcSessionRegistry sessionRegistry,
            SessionRepository<?> sessions,
            ProblemResponses problems,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimits,
            Clock clock,
            StringRedisTemplate redis,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeTokenClient,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenClient,
            JwtDecoderFactory<ClientRegistration> idTokenDecoders,
            JsonMapper jsonMapper,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled)
            throws Exception {
        CsrfTokenRepository csrfTokens = csrfTokenRepository();
        OAuth2AuthorizedClientRepository authorizedClients = new HttpSessionOAuth2AuthorizedClientRepository();
        LogoutHandler backChannelLogout = new SessionDeletingLogoutHandler(sessionRegistry, sessions);
        AbsoluteSessionTimeoutFilter absoluteTimeout =
                new AbsoluteSessionTimeoutFilter(clock, properties.absoluteSessionTimeout(), csrfTokens);
        RevalidationFilter revalidation = new RevalidationFilter(
                clock,
                properties,
                new IdentityRefresher(authorizedClients, refreshTokenClient, idTokenDecoders),
                redis,
                csrfTokens,
                problems);

        http.authorizeHttpRequests(requests -> {
                    requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    if (apiDocsEnabled) {
                        requests.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**")
                                .permitAll();
                    }
                    requests.requestMatchers(EndpointRequest.to(HealthEndpoint.class), EndpointRequest.toLinks())
                            .permitAll()
                            .requestMatchers(
                                    HttpMethod.GET, "/api/status", "/api/categories", "/api/products/**", "/api/me")
                            .permitAll()
                            .requestMatchers("/api/admin/**")
                            .hasRole("ADMIN")
                            .requestMatchers(
                                    "/api/cart",
                                    "/api/cart/**",
                                    "/api/checkout",
                                    "/api/orders",
                                    "/api/orders/**",
                                    "/api/fake-psp/**")
                            .hasRole("CUSTOMER")
                            .anyRequest()
                            .denyAll();
                })
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .oauth2Login(
                        login -> login.loginPage("/oauth2/authorization/" + KeycloakClientRegistration.REGISTRATION_ID)
                                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestRepository(
                                        new LoginAuthorizationRequestRepository(properties.loginSessionTimeout())))
                                .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(codeTokenClient))
                                .userInfoEndpoint(userInfo ->
                                        userInfo.userAuthoritiesMapper(ShopAuthorities::mapLoginAuthorities))
                                .authorizedClientRepository(authorizedClients)
                                .successHandler(new LoginSuccessHandler(clock, properties))
                                .failureHandler(new LoginFailureHandler(properties, new DefaultRedirectStrategy())))
                .oidcLogout(logout -> logout.oidcSessionRegistry(sessionRegistry)
                        .backChannel(backChannel -> backChannel.logoutHandler(backChannelLogout)))
                .logout(logout ->
                        logout.logoutSuccessHandler(new LogoutRedirectHandler(registrations, properties, jsonMapper)))
                .addFilterAfter(absoluteTimeout, SecurityContextHolderFilter.class)
                .addFilterAfter(revalidation, AbsoluteSessionTimeoutFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimits, rateLimiter, problems), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    ProblemResponses problemResponses(JsonMapper jsonMapper) {
        return new ProblemResponses(jsonMapper);
    }

    /** The firewall rejects malformed paths before MVC runs; answer them as Problem Details like every other 400. */
    @Bean
    RequestRejectedHandler requestRejectedHandler(ProblemResponses problems) {
        return (request, response, rejection) ->
                problems.write(response, HttpStatus.BAD_REQUEST, "The request was rejected");
    }

    @Bean
    FilterRegistrationBean<ValkeyFailureFilter> valkeyFailureFilter(ProblemResponses problems) {
        FilterRegistrationBean<ValkeyFailureFilter> registration =
                new FilterRegistrationBean<>(new ValkeyFailureFilter(problems));
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        return registration;
    }

    private static CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE);
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Strict"));
        return repository;
    }
}
