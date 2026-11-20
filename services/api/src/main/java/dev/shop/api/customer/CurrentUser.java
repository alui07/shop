package dev.shop.api.customer;

import dev.shop.api.web.PlainTextRules;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Who is acting: the stable Keycloak subject for decisions and records, a display name for people to read, and the
 * email address only when the identity provider says it is verified.
 */
public record CurrentUser(String subject, String displayName, String verifiedEmail) {

    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 200;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^\\s\\p{Cc}\\p{Z}@]+@[^\\s\\p{Cc}\\p{Z}@]+");

    public CurrentUser(String subject, String displayName) {
        this(subject, displayName, null);
    }

    public static CurrentUser of(Authentication authentication) {
        if (!(authentication != null && authentication.getPrincipal() instanceof OidcUser user)
                || user.getSubject() == null
                || user.getSubject().isBlank()) {
            throw new AccessDeniedException("Not signed in through OpenID Connect");
        }
        return new CurrentUser(user.getSubject(), displayName(user), verifiedEmail(user));
    }

    /** Only a claim that is the JSON boolean true counts; a string "true" does not. */
    private static String verifiedEmail(OidcUser user) {
        String email = user.getEmail();
        boolean verified = Boolean.TRUE.equals(user.getClaims().get("email_verified"));
        return verified
                        && email != null
                        && email.length() <= MAX_EMAIL_LENGTH
                        && EMAIL_SHAPE.matcher(email).matches()
                ? email
                : null;
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
