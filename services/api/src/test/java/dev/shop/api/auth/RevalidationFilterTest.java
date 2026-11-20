package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

class RevalidationFilterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final IdentityRefresher refresher = mock(IdentityRefresher.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final CsrfTokenRepository csrfTokens = mock(CsrfTokenRepository.class);
    private final ProblemResponses problems =
            new ProblemResponses(JsonMapper.builder().build());

    private final ShopAuthProperties properties = new ShopAuthProperties(
            "https://shop.example.test",
            "https://idp.example.test/realms/shop",
            "http://keycloak/realms/shop",
            "shop-api",
            "secret",
            Duration.ofHours(8),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15));
    private final RevalidationFilter filter = new RevalidationFilter(
            Clock.fixed(NOW, ZoneOffset.UTC), properties, refresher, redis, csrfTokens, problems);

    private MockHttpSession session;
    private MockHttpServletRequest request;
    private OAuth2AuthenticationToken signedIn;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        OidcIdToken idToken = OidcIdToken.withTokenValue("t")
                .subject("sub-1")
                .issuer("https://idp.example.test/realms/shop")
                .build();
        signedIn = new OAuth2AuthenticationToken(
                new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken), AuthorityUtils.NO_AUTHORITIES, "keycloak");
        SecurityContextHolder.getContext().setAuthentication(signedIn);
        session = new MockHttpSession();
        request = new MockHttpServletRequest();
        request.setSession(session);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ignoresAuthenticationsThatAreNotOidcLogins() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("u", "n/a", List.of()));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verifyNoInteractions(refresher);
    }

    @Test
    void ignoresRequestsWithoutASession() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        verifyNoInteractions(refresher);
    }

    @Test
    void doesNothingBeforeTheIntervalHasPassed() throws Exception {
        session.setAttribute(SessionAttributes.LAST_REVALIDATION_ATTEMPT, NOW.minusSeconds(60));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verifyNoInteractions(refresher);
    }

    @Test
    void aSessionThatWasNeverCheckedIsDueAtOnce() throws Exception {
        when(refresher.refresh(any(), any(), any())).thenReturn(new IdentityRefresher.Refreshed(signedIn));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(refresher).refresh(any(), any(), any());
        assertThat(session.getAttribute(SessionAttributes.LAST_REVALIDATION_SUCCESS))
                .isEqualTo(NOW);
        verify(values).setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(20)));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void aRequestThatLosesTheLockCarriesOnWithoutRefreshing() throws Exception {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verifyNoInteractions(refresher);
        verify(redis, never()).delete(anyString());
    }

    @Test
    void unavailableWithoutAnyRecordedSuccessEndsTheSession() throws Exception {
        when(refresher.refresh(any(), any(), any())).thenReturn(new IdentityRefresher.Unavailable());

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aRefusedRevalidationAlsoClearsTheCsrfToken() throws Exception {
        when(refresher.refresh(any(), any(), any())).thenReturn(new IdentityRefresher.Rejected());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(session.isInvalid()).isTrue();
        verify(csrfTokens).saveToken(null, request, response);
    }

    private MockFilterChain adminWrite(String method, String uri) throws Exception {
        request.setMethod(method);
        request.setRequestURI(uri);
        MockFilterChain chain = new MockFilterChain();
        responseOfLastRun = new MockHttpServletResponse();
        filter.doFilter(request, responseOfLastRun, chain);
        return chain;
    }

    private MockHttpServletResponse responseOfLastRun;

    private void notDueForRevalidation(Instant lastSuccess) {
        session.setAttribute(SessionAttributes.LAST_REVALIDATION_ATTEMPT, NOW.minusSeconds(60));
        if (lastSuccess != null) {
            session.setAttribute(SessionAttributes.LAST_REVALIDATION_SUCCESS, lastSuccess);
        }
    }

    @Test
    void anAdminWriteWithoutAnyRecordedSuccessIsA503Problem() throws Exception {
        notDueForRevalidation(null);

        MockFilterChain chain = adminWrite("PUT", "/api/admin/products/1");

        assertThat(responseOfLastRun.getStatus()).isEqualTo(503);
        assertThat(responseOfLastRun.getContentType()).startsWith("application/problem+json");
        assertThat(responseOfLastRun.getContentAsString()).contains("Your access cannot be confirmed right now");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void aPercentEncodedAdminPathIsStillAnAdminPath() throws Exception {
        notDueForRevalidation(null);

        MockFilterChain chain = adminWrite("PUT", "/api/%61dmin/products/1");

        assertThat(responseOfLastRun.getStatus()).isEqualTo(503);
        assertThat(responseOfLastRun.getHeader("Retry-After")).isEqualTo("300");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void anAdminWriteWithAnOverdueSuccessIsA503Problem() throws Exception {
        notDueForRevalidation(NOW.minus(Duration.ofMinutes(5)));

        MockFilterChain chain = adminWrite("POST", "/api/admin/products");

        assertThat(responseOfLastRun.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void anAdminWriteWithAFreshSuccessPasses() throws Exception {
        notDueForRevalidation(NOW.minus(Duration.ofMinutes(5)).plusSeconds(1));

        MockFilterChain chain = adminWrite("PUT", "/api/admin/products/1");

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void safeMethodsAndOtherPathsKeepTheGrace() throws Exception {
        notDueForRevalidation(null);

        for (String method : new String[] {"GET", "HEAD", "OPTIONS", "TRACE"}) {
            assertThat(adminWrite(method, "/api/admin/products").getRequest())
                    .as(method)
                    .isNotNull();
        }
        assertThat(adminWrite("POST", "/api/me").getRequest()).isNotNull();
    }

    @Test
    void authenticationsThatAreNotOidcLoginsAreLeftToTheOtherRules() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("u", "n/a", List.of()));

        assertThat(adminWrite("PUT", "/api/admin/products/1").getRequest()).isNotNull();
    }

    @Test
    void aSignedInAdminWriteWithoutASessionIsA503Problem() throws Exception {
        request = new MockHttpServletRequest("PUT", "/api/admin/products/1");
        responseOfLastRun = new MockHttpServletResponse();

        filter.doFilter(request, responseOfLastRun, new MockFilterChain());

        assertThat(responseOfLastRun.getStatus()).isEqualTo(503);
    }

    @Test
    void aSuccessfulRefreshInTheSameRequestLetsAnAdminWritePass() throws Exception {
        when(refresher.refresh(any(), any(), any())).thenReturn(new IdentityRefresher.Refreshed(signedIn));

        MockFilterChain chain = adminWrite("PUT", "/api/admin/products/1");

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void anAdminWriteWhoseSessionWasEndedByARefusalFallsThroughToTheChain() throws Exception {
        when(refresher.refresh(any(), any(), any())).thenReturn(new IdentityRefresher.Rejected());

        MockFilterChain chain = adminWrite("PUT", "/api/admin/products/1");

        assertThat(chain.getRequest()).isNotNull();
        assertThat(responseOfLastRun.getStatus()).isEqualTo(200);
    }
}
