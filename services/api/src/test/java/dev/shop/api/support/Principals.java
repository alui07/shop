package dev.shop.api.support;

import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/** Signed-in users for tests that call services directly instead of going through the login. */
public final class Principals {

    private Principals() {}

    public static Authentication customer(String subject) {
        return withRoles(subject, "ROLE_CUSTOMER");
    }

    public static Authentication withRoles(String subject, String... authorities) {
        OidcIdToken token = OidcIdToken.withTokenValue("token")
                .subject(subject)
                .claim("email", subject + "@example.test")
                .claim("email_verified", true)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        List<SimpleGrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        DefaultOidcUser user = new DefaultOidcUser(granted, token);
        return new OAuth2AuthenticationToken(user, granted, "keycloak");
    }
}
