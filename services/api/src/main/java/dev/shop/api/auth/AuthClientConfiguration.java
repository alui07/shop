package dev.shop.api.auth;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShopAuthProperties.class)
class AuthClientConfiguration {

    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(ShopAuthProperties properties) {
        return new InMemoryClientRegistrationRepository(KeycloakClientRegistration.from(properties));
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        return new IdTokenDecoderFactory();
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenResponseClient() {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(AuthHttp.tokenEndpointClient());
        return client;
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenResponseClient() {
        RestClientRefreshTokenTokenResponseClient client = new RestClientRefreshTokenTokenResponseClient();
        client.setRestClient(AuthHttp.tokenEndpointClient());
        return client;
    }
}
