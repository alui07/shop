package dev.shop.api.customer;

import dev.shop.api.web.PlainTextRules;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Who is acting: the stable Keycloak subject for decisions and records, a display name for people to read. */
public record CurrentUser(String subject, String displayName) {

    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 200;

    public static CurrentUser of(Authentication authentication) {
        if (!(authentication != null && authentication.getPrincipal() instanceof OidcUser user)
                || user.getSubject() == null
                || user.getSubject().isBlank()) {
            throw new AccessDeniedException("Not signed in through OpenID Connect");
        }
        return new CurrentUser(user.getSubject(), displayName(user));
    }

    private static String displayName(OidcUser user) {
        for (String candidate : new String[] {user.getFullName(), user.getPreferredUsername()}) {
            String name = candidate == null ? "" : PlainTextRules.withoutDisallowed(candidate);
            if (!name.isBlank()) {
                return truncate(name);
            }
        }
        return truncate(PlainTextRules.withoutDisallowed(user.getSubject()));
    }

    private static String truncate(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_DISPLAY_NAME_CODE_POINTS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_DISPLAY_NAME_CODE_POINTS));
    }
}
