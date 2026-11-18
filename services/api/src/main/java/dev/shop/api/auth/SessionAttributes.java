package dev.shop.api.auth;

import jakarta.servlet.http.HttpSession;

/** Session attribute names. Values are {@link java.time.Instant}s. */
final class SessionAttributes {

    static final String AUTHENTICATED_AT = "shop.auth.authenticatedAt";
    static final String LAST_REVALIDATION_ATTEMPT = "shop.auth.lastRevalidationAttempt";
    static final String LAST_REVALIDATION_SUCCESS = "shop.auth.lastRevalidationSuccess";

    /** A session is signed in once the login success handler has stamped it. */
    static boolean isSignedIn(HttpSession session) {
        return session.getAttribute(AUTHENTICATED_AT) != null;
    }

    private SessionAttributes() {}
}
