package dev.shop.api.customer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The only roles the shop knows. Anything else in a token's roles claim is ignored. */
public final class ShopRoles {

    public static final String CUSTOMER = "customer";
    public static final String ADMIN = "admin";

    private static final String AUTHORITY_PREFIX = "ROLE_";
    private static final Map<String, String> AUTHORITY_BY_ROLE = Map.of(
            CUSTOMER, AUTHORITY_PREFIX + "CUSTOMER",
            ADMIN, AUTHORITY_PREFIX + "ADMIN");

    private ShopRoles() {}

    /** Maps a token's roles claim (any shape) to authorities, dropping everything outside the allowlist. */
    public static List<GrantedAuthority> authoritiesFor(Object rolesClaim) {
        if (!(rolesClaim instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .map(AUTHORITY_BY_ROLE::get)
                .filter(authority -> authority != null)
                .distinct()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }

    /** The role names (the inverse of {@link #authoritiesFor}) held by the given authorities. */
    public static List<String> rolesOf(Collection<? extends GrantedAuthority> authorities) {
        return AUTHORITY_BY_ROLE.entrySet().stream()
                .filter(role ->
                        authorities.stream().anyMatch(a -> role.getValue().equals(a.getAuthority())))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
