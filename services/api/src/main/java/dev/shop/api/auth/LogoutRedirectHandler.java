package dev.shop.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * RP-initiated logout for a SPA: a {@code fetch} cannot follow a cross-origin redirect, so the Keycloak logout URL
 * (with {@code id_token_hint}) comes back as JSON and the SPA navigates to it.
 */
final class LogoutRedirectHandler extends OidcClientInitiatedLogoutSuccessHandler {

    private final String endSessionEndpoint;
    private final String clientId;

    LogoutRedirectHandler(
            ClientRegistrationRepository registrations, ShopAuthProperties properties, JsonMapper jsonMapper) {
        super(registrations);
        this.endSessionEndpoint = registrations
                .findByRegistrationId(KeycloakClientRegistration.REGISTRATION_ID)
                .getProviderDetails()
                .getConfigurationMetadata()
                .get(KeycloakClientRegistration.END_SESSION_ENDPOINT)
                .toString();
        this.clientId = properties.clientId();
        setPostLogoutRedirectUri(properties.publicHome());
        setDefaultTargetUrl(properties.publicHome());
        setRedirectStrategy((request, response, url) -> writeJson(response, jsonMapper, url));
    }

    @Override
    protected String determineTargetUrl(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        String url = super.determineTargetUrl(request, response, authentication);
        if (!url.startsWith(endSessionEndpoint)) {
            return url;
        }
        return UriComponentsBuilder.fromUriString(url)
                .queryParam("client_id", clientId)
                .build()
                .toUriString();
    }

    private static void writeJson(HttpServletResponse response, JsonMapper jsonMapper, String url) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(jsonMapper.writeValueAsBytes(Map.of("redirectUrl", url)));
    }
}
