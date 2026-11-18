package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

class IdTokenDecoderFactoryTest {

    private static ClientRegistration registration(String jwkSetUri) {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId("shop-api")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://shop.example.test/login/oauth2/code/keycloak")
                .authorizationUri("https://idp/auth")
                .tokenUri("http://kc/token")
                .jwkSetUri(jwkSetUri)
                .build();
    }

    @Test
    void decodersAreCachedPerRegistration() {
        IdTokenDecoderFactory factory = new IdTokenDecoderFactory();
        ClientRegistration registration = registration("http://localhost:1/certs");

        assertThat(factory.createDecoder(registration)).isSameAs(factory.createDecoder(registration));
    }

    @Test
    void aJwkSetUriThatIsNotAUrlIsRejected() {
        IdTokenDecoderFactory factory = new IdTokenDecoderFactory();

        assertThatThrownBy(() -> factory.createDecoder(registration("unknown://host/certs")))
                .isInstanceOf(IllegalStateException.class);
    }
}
