package dev.shop.api.customer;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    @GetMapping(path = "/api/me", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Me> me(@AuthenticationPrincipal OidcUser user, Authentication authentication) {
        Me me = user == null ? Me.anonymous() : signedIn(user, authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(me);
    }

    private static Me signedIn(OidcUser user, Authentication authentication) {
        List<Me.Role> roles = ShopRoles.rolesOf(authentication.getAuthorities()).stream()
                .map(Me.Role::valueOf)
                .toList();
        return new Me(true, user.getSubject(), displayName(user), user.getEmail(), roles);
    }

    private static String displayName(OidcUser user) {
        String fullName = user.getFullName();
        return fullName != null ? fullName : user.getPreferredUsername();
    }
}
