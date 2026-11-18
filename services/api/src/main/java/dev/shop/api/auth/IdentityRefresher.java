package dev.shop.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Asks Keycloak whether the user may still hold this session. A refresh-token grant fails when the user was disabled
 * or signed out, and its ID token carries the current roles. Spring's authorized-client manager is not used because it
 * refreshes only expired access tokens and drops the ID token.
 */
final class IdentityRefresher {

    sealed interface Outcome {}

    record Refreshed(OAuth2AuthenticationToken authentication) implements Outcome {}

    /** Keycloak says the session is over (revoked or expired refresh token, disabled user). Fail closed. */
    record Rejected() implements Outcome {}

    /** Keycloak could not be asked or gave no verdict. The session may continue for a grace period. */
    record Unavailable() implements Outcome {}

    private static final Logger LOGGER = LoggerFactory.getLogger(IdentityRefresher.class);

    private final OAuth2AuthorizedClientRepository authorizedClients;
    private final OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient;
    private final JwtDecoderFactory<ClientRegistration> decoders;

    IdentityRefresher(
            OAuth2AuthorizedClientRepository authorizedClients,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient,
            JwtDecoderFactory<ClientRegistration> decoders) {
        this.authorizedClients = authorizedClients;
        this.refreshClient = refreshClient;
        this.decoders = decoders;
    }

    Outcome refresh(OAuth2AuthenticationToken current, HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizedClient client =
                authorizedClients.loadAuthorizedClient(current.getAuthorizedClientRegistrationId(), current, request);
        if (client == null || client.getRefreshToken() == null) {
            return new Rejected();
        }
        OAuth2AccessTokenResponse tokens;
        try {
            tokens = refreshClient.getTokenResponse(new OAuth2RefreshTokenGrantRequest(
                    client.getClientRegistration(), client.getAccessToken(), client.getRefreshToken()));
        } catch (OAuth2AuthorizationException e) {
            LOGGER.info("Session revalidation: {}", e.getError().getErrorCode());
            return OAuth2ErrorCodes.INVALID_GRANT.equals(e.getError().getErrorCode())
                    ? new Rejected()
                    : new Unavailable();
        }
        OAuth2RefreshToken refreshToken = tokens.getRefreshToken();
        authorizedClients.saveAuthorizedClient(
                new OAuth2AuthorizedClient(
                        client.getClientRegistration(),
                        client.getPrincipalName(),
                        tokens.getAccessToken(),
                        refreshToken),
                current,
                request,
                response);
        return reauthenticate(current, client.getClientRegistration(), tokens);
    }

    private Outcome reauthenticate(
            OAuth2AuthenticationToken current, ClientRegistration registration, OAuth2AccessTokenResponse tokens) {
        Object rawIdToken = tokens.getAdditionalParameters().get(OidcParameterNames.ID_TOKEN);
        if (rawIdToken == null) {
            LOGGER.info("Session revalidation returned no ID token");
            return new Unavailable();
        }
        try {
            Jwt jwt = decoders.createDecoder(registration).decode(rawIdToken.toString());
            OidcIdToken idToken =
                    new OidcIdToken(jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
            if (!idToken.getSubject().equals(current.getName())) {
                return new Rejected();
            }
            List<GrantedAuthority> authorities = ShopAuthorities.from(idToken);
            OidcUser user = new DefaultOidcUser(authorities, idToken, "sub");
            return new Refreshed(
                    new OAuth2AuthenticationToken(user, authorities, current.getAuthorizedClientRegistrationId()));
        } catch (BadJwtException e) {
            LOGGER.info("Session revalidation returned an invalid ID token");
            return new Rejected();
        } catch (JwtException e) {
            LOGGER.info(
                    "Session revalidation could not verify the ID token: {}",
                    e.getClass().getSimpleName());
            return new Unavailable();
        }
    }
}
