package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

    private final RateLimiter limiter = mock(RateLimiter.class);
    private final ProblemResponses problems =
            new ProblemResponses(JsonMapper.builder().build());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private RateLimitFilter filter(boolean enabled) {
        return new RateLimitFilter(new RateLimitProperties(enabled, 1), limiter, problems);
    }

    private static void signInAs(String subject) {
        OidcIdToken token =
                new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60), Map.of("sub", subject));
        DefaultOidcUser user = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")), token);
        SecurityContextHolder.getContext()
                .setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak"));
    }

    private boolean passes(RateLimitFilter filter, MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        return called.get();
    }

    @Test
    void anAllowedRequestContinuesAndTakesATokenForTheAccountAndRule() throws Exception {
        signInAs("sub-1");
        when(limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1")).thenReturn(RateLimiter.Decision.ALLOWED);

        boolean passed = passes(
                filter(true), new MockHttpServletRequest("POST", "/api/checkout"), new MockHttpServletResponse());

        assertThat(passed).isTrue();
        verify(limiter).tryConsume(RateLimitRule.CHECKOUT, "sub-1");
    }

    @Test
    void aRejectedRequestGetsA429ProblemWithRetryAfterAndNeverReachesTheController() throws Exception {
        signInAs("sub-1");
        when(limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1")).thenReturn(new RateLimiter.Decision(false, 7));
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean passed = passes(filter(true), new MockHttpServletRequest("POST", "/api/checkout"), response);

        assertThat(passed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("7");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.get("type").asString()).isEqualTo("tag:shop.dev,2026:rate-limited");
        assertThat(body.get("title").asString()).isEqualTo("Too Many Requests");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("detail").asString()).isEqualTo("Too many requests; try again later");
        assertThat(body.get("errors")).isEmpty();
        assertThat(response.getContentAsString()).doesNotContain("sub-1");
    }

    @Test
    void whenDisabledNothingIsCounted() throws Exception {
        signInAs("sub-1");

        boolean passed = passes(
                filter(false), new MockHttpServletRequest("POST", "/api/checkout"), new MockHttpServletResponse());

        assertThat(passed).isTrue();
        verifyNoInteractions(limiter);
    }

    @Test
    void requestsNoRuleCoversAreNotCounted() throws Exception {
        signInAs("sub-1");

        boolean passed =
                passes(filter(true), new MockHttpServletRequest("GET", "/api/cart"), new MockHttpServletResponse());

        assertThat(passed).isTrue();
        verifyNoInteractions(limiter);
    }

    @Test
    void anonymousAndNonOidcPrincipalsAreNotCounted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/checkout");
        assertThat(passes(filter(true), request, new MockHttpServletResponse())).isTrue();

        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("crafted", "n/a", "ROLE_CUSTOMER"));
        assertThat(passes(filter(true), request, new MockHttpServletResponse())).isTrue();

        verifyNoInteractions(limiter);
    }

    @Test
    void aMissingOrBlankSubjectIsNotCounted() throws Exception {
        for (String subject : new String[] {null, "   "}) {
            OidcUser user = mock(OidcUser.class);
            when(user.getSubject()).thenReturn(subject);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, "n/a"));

            boolean passed = passes(
                    filter(true), new MockHttpServletRequest("POST", "/api/checkout"), new MockHttpServletResponse());

            assertThat(passed).isTrue();
        }

        verifyNoInteractions(limiter);
    }
}
