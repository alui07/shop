package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

class IdentityRefresherTest {

    private final OAuth2AuthorizedClientRepository clients = mock(OAuth2AuthorizedClientRepository.class);

    @SuppressWarnings("unchecked")
    private final OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient =
            mock(OAuth2AccessTokenResponseClient.class);

    @SuppressWarnings("unchecked")
    private final JwtDecoderFactory<ClientRegistration> decoders = mock(JwtDecoderFactory.class);

    private final IdentityRefresher refresher = new IdentityRefresher(clients, refreshClient, decoders);

    private final OAuth2AuthenticationToken current = new OAuth2AuthenticationToken(
            new DefaultOidcUser(
                    AuthorityUtils.NO_AUTHORITIES,
                    OidcIdToken.withTokenValue("t").subject("sub-1").build()),
            AuthorityUtils.NO_AUTHORITIES,
            "keycloak");

    @Test
    void aSessionWithoutAnAuthorizedClientIsRejected() {
        when(clients.loadAuthorizedClient(eq("keycloak"), any(), any())).thenReturn(null);

        assertThat(refresher.refresh(current, new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(IdentityRefresher.Rejected.class);
        verifyNoInteractions(refreshClient);
    }

    @Test
    void anAuthorizedClientWithoutARefreshTokenIsRejected() {
        ClientRegistration registration = KeycloakClientRegistration.from(new ShopAuthProperties(
                "https://shop.example.test/",
                "https://idp.example.test/realms/shop/",
                "http://keycloak/realms/shop/",
                "shop-api",
                "secret",
                java.time.Duration.ofHours(8),
                java.time.Duration.ofMinutes(30),
                java.time.Duration.ofMinutes(5),
                java.time.Duration.ofMinutes(5),
                java.time.Duration.ofMinutes(15)));
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "a",
                java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(60));
        when(clients.loadAuthorizedClient(eq("keycloak"), any(), any()))
                .thenReturn(new OAuth2AuthorizedClient(registration, "sub-1", accessToken, null));

        assertThat(refresher.refresh(current, new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(IdentityRefresher.Rejected.class);
        verifyNoInteractions(refreshClient);
    }

    private Object refreshReturningIdToken(
            org.springframework.security.oauth2.jwt.JwtDecoder decoder, boolean withIdToken) {
        ClientRegistration registration = KeycloakClientRegistration.from(new ShopAuthProperties(
                "https://shop.example.test/",
                "https://idp.example.test/realms/shop/",
                "http://keycloak/realms/shop/",
                "shop-api",
                "secret",
                java.time.Duration.ofHours(8),
                java.time.Duration.ofMinutes(30),
                java.time.Duration.ofMinutes(5),
                java.time.Duration.ofMinutes(5),
                java.time.Duration.ofMinutes(15)));
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "a",
                java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(60));
        when(clients.loadAuthorizedClient(eq("keycloak"), any(), any()))
                .thenReturn(new OAuth2AuthorizedClient(
                        registration,
                        "sub-1",
                        accessToken,
                        new org.springframework.security.oauth2.core.OAuth2RefreshToken("r", java.time.Instant.now())));
        var response = org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse.withToken("a2")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .refreshToken("r2")
                .additionalParameters(withIdToken ? java.util.Map.of("id_token", "raw") : java.util.Map.of())
                .build();
        when(refreshClient.getTokenResponse(any())).thenReturn(response);
        when(decoders.createDecoder(any())).thenReturn(decoder);
        return refresher.refresh(current, new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void aBadIdTokenIsRejected() {
        var decoder = mock(org.springframework.security.oauth2.jwt.JwtDecoder.class);
        when(decoder.decode("raw")).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("bad"));

        assertThat(refreshReturningIdToken(decoder, true)).isInstanceOf(IdentityRefresher.Rejected.class);
    }

    @Test
    void aKeySourceFailureWhileCheckingTheIdTokenIsUnavailable() {
        var decoder = mock(org.springframework.security.oauth2.jwt.JwtDecoder.class);
        when(decoder.decode("raw")).thenThrow(new org.springframework.security.oauth2.jwt.JwtException("jwks down"));

        assertThat(refreshReturningIdToken(decoder, true)).isInstanceOf(IdentityRefresher.Unavailable.class);
    }

    @Test
    void aRefreshWithoutAnIdTokenIsUnavailable() {
        var decoder = mock(org.springframework.security.oauth2.jwt.JwtDecoder.class);

        assertThat(refreshReturningIdToken(decoder, false)).isInstanceOf(IdentityRefresher.Unavailable.class);
    }
}
