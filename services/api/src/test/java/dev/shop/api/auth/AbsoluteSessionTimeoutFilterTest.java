package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfTokenRepository;

class AbsoluteSessionTimeoutFilterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final CsrfTokenRepository csrfTokens = mock(CsrfTokenRepository.class);
    private final AbsoluteSessionTimeoutFilter filter =
            new AbsoluteSessionTimeoutFilter(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(8), csrfTokens);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void leavesAnonymousRequestsAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verifyNoInteractions(csrfTokens);
    }

    @Test
    void leavesRequestsWithoutAnyAuthenticationAlone() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(csrfTokens);
    }

    @Test
    void anAuthenticationWithoutAnyHttpSessionIsExpired() throws Exception {
        signIn();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aStampedSessionInsideTheTimeoutIsKept() throws Exception {
        signIn();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.AUTHENTICATED_AT, NOW.minus(Duration.ofHours(7)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void aSessionAtTheTimeoutIsEndedAndItsCsrfTokenCleared() throws Exception {
        signIn();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.AUTHENTICATED_AT, NOW.minus(Duration.ofHours(8)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(csrfTokens).saveToken(null, request, response);
    }

    @Test
    void aSessionWithAnUnexpectedStampTypeIsExpired() throws Exception {
        signIn();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.AUTHENTICATED_AT, "yesterday");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isTrue();
    }

    private static void signIn() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user", "n/a", List.of()));
    }
}
