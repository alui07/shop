package dev.shop.api.auth;

import dev.shop.api.customer.ShopRoles;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;

/** Turns the roles claim of an ID token into authorities, and nothing else (no scopes, no OIDC marker authority). */
final class ShopAuthorities {

    static final String ROLES_CLAIM = "roles";

    private ShopAuthorities() {}

    static List<GrantedAuthority> from(OidcIdToken idToken) {
        return ShopRoles.authoritiesFor(idToken.getClaim(ROLES_CLAIM));
    }

    /** The mapper Spring applies after login: it looks for the ID token among the default authorities. */
    static Collection<GrantedAuthority> mapLoginAuthorities(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .filter(OidcUserAuthority.class::isInstance)
                .map(OidcUserAuthority.class::cast)
                .map(OAuth2UserAuthority::getAttributes)
                .map(attributes -> attributes.get(ROLES_CLAIM))
                .map(ShopRoles::authoritiesFor)
                .flatMap(List::stream)
                .distinct()
                .toList();
    }
}
