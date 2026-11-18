package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.DefaultRedirectStrategy;

class LoginHandlersTest {

    private static final ShopAuthProperties PROPERTIES = new ShopAuthProperties(
            "https://shop.example.test",
            "https://idp/realms/shop",
            "http://kc/realms/shop",
            "shop-api",
            "secret",
            Duration.ofHours(8),
            Duration.ofMinutes(30),
            Duration.ofMinutes(5),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15));

    private final LoginFailureHandler failureHandler =
            new LoginFailureHandler(PROPERTIES, new DefaultRedirectStrategy());

    @Test
    void anOAuth2FailureRedirectsHomeAndDropsTheSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(
                request, response, new OAuth2AuthenticationException(new OAuth2Error("invalid_token_response")));

        assertThat(response.getRedirectedUrl()).isEqualTo("https://shop.example.test/?login=failed");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void anyOtherFailureWithoutASessionStillRedirectsHome() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(
                new MockHttpServletRequest(), response, new BadCredentialsException("nope"));

        assertThat(response.getRedirectedUrl()).isEqualTo("https://shop.example.test/?login=failed");
    }

    @Test
    void theAuthorizationRequestRepositoryLoadsWhatItSaved() {
        LoginAuthorizationRequestRepository repository = new LoginAuthorizationRequestRepository(Duration.ofMinutes(5));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("state", "s");
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://idp/auth")
                .clientId("shop-api")
                .state("s")
                .build();

        repository.saveAuthorizationRequest(authorizationRequest, request, new MockHttpServletResponse());

        assertThat(repository.loadAuthorizationRequest(request)).isEqualTo(authorizationRequest);
        assertThat(request.getSession().getMaxInactiveInterval()).isEqualTo(300);
    }

    @Test
    void aSignedInSessionSurvivesAFailedCallbackAndKeepsItsIdleTimeout() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = signedInSession();
        request.setSession(session);

        failureHandler.onAuthenticationFailure(
                request, new MockHttpServletResponse(), new OAuth2AuthenticationException(new OAuth2Error("x")));

        assertThat(session.isInvalid()).isFalse();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(1800);
    }

    @Test
    void startingALoginInASignedInSessionKeepsItsIdleTimeout() {
        LoginAuthorizationRequestRepository repository = new LoginAuthorizationRequestRepository(Duration.ofMinutes(5));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = signedInSession();
        request.setSession(session);

        repository.saveAuthorizationRequest(
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri("https://idp/auth")
                        .clientId("shop-api")
                        .state("s")
                        .build(),
                request,
                new MockHttpServletResponse());

        assertThat(session.getMaxInactiveInterval()).isEqualTo(1800);
    }

    private static MockHttpSession signedInSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.AUTHENTICATED_AT, java.time.Instant.now());
        session.setMaxInactiveInterval(1800);
        return session;
    }
}
