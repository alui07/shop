package dev.shop.api.auth;

import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

/** Every call from the API to Keycloak is bounded, so a slow Keycloak cannot pin request threads. */
final class AuthHttp {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private AuthHttp() {}

    static RestClient tokenEndpointClient() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .configureMessageConverters(converters -> converters
                        .registerDefaults()
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .requestFactory(requestFactory)
                .build();
    }

    static ResourceRetriever jwksRetriever() {
        return new DefaultResourceRetriever(
                Math.toIntExact(CONNECT_TIMEOUT.toMillis()), Math.toIntExact(READ_TIMEOUT.toMillis()), 51_200);
    }
}
