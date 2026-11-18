package dev.shop.api.auth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Spring's default factory fetches the JWK set without timeouts, so this one builds the same decoder (RS256, the
 * standard ID token validator and claim conversion) on a bounded retriever.
 */
final class IdTokenDecoderFactory implements JwtDecoderFactory<ClientRegistration> {

    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    @Override
    public JwtDecoder createDecoder(ClientRegistration registration) {
        return decoders.computeIfAbsent(registration.getRegistrationId(), id -> build(registration));
    }

    private static JwtDecoder build(ClientRegistration registration) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource(registration))
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new OidcIdTokenValidator(registration));
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        return decoder;
    }

    private static JWKSource<SecurityContext> jwkSource(ClientRegistration registration) {
        try {
            return JWKSourceBuilder.create(
                            URI.create(registration.getProviderDetails().getJwkSetUri())
                                    .toURL(),
                            AuthHttp.jwksRetriever())
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("The JWK set URI is not a URL", e);
        }
    }
}
