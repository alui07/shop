package dev.shop.api.auth;

import java.util.Map;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Built without discovery. Keycloak stamps {@code iss} from its public URL, but the API can only reach it on the
 * internal URL, so the browser-facing endpoints and the issuer use the public URL and the server-to-server ones the
 * internal URL.
 */
final class KeycloakClientRegistration {

    static final String REGISTRATION_ID = "keycloak";
    static final String END_SESSION_ENDPOINT = "end_session_endpoint";

    private static final String OPENID_CONNECT = "/protocol/openid-connect";

    private KeycloakClientRegistration() {}

    static ClientRegistration from(ShopAuthProperties properties) {
        String issuer = ShopAuthProperties.withoutTrailingSlash(properties.issuer());
        String internalRealm = ShopAuthProperties.withoutTrailingSlash(properties.internalRealmUrl());
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.publicOriginWithoutTrailingSlash() + "/login/oauth2/code/" + REGISTRATION_ID)
                .scope("openid", "profile", "email")
                .issuerUri(issuer)
                .authorizationUri(issuer + OPENID_CONNECT + "/auth")
                .tokenUri(internalRealm + OPENID_CONNECT + "/token")
                .jwkSetUri(internalRealm + OPENID_CONNECT + "/certs")
                .providerConfigurationMetadata(Map.of(END_SESSION_ENDPOINT, issuer + OPENID_CONNECT + "/logout"))
                .userNameAttributeName("sub")
                .clientName("Keycloak")
                .clientSettings(ClientRegistration.ClientSettings.builder()
                        .requireProofKey(true)
                        .build())
                .build();
    }
}
