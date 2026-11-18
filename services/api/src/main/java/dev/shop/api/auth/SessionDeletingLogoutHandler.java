package dev.shop.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionInformation;
import org.springframework.security.oauth2.client.oidc.session.OidcSessionRegistry;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.session.SessionRepository;

/**
 * Back-channel logout: deletes the app session in the session store directly. Spring's default handler instead calls
 * {@code /logout} on the API with a replayed {@code JSESSIONID}, which does not work with {@code __Host-SESSION}.
 */
final class SessionDeletingLogoutHandler implements LogoutHandler {

    private final OidcSessionRegistry registry;
    private final SessionRepository<?> sessions;

    SessionDeletingLogoutHandler(OidcSessionRegistry registry, SessionRepository<?> sessions) {
        this.registry = registry;
        this.sessions = sessions;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        OidcLogoutToken token = (OidcLogoutToken) authentication.getPrincipal();
        for (OidcSessionInformation session : registry.removeSessionInformation(token)) {
            sessions.deleteById(session.getSessionId());
        }
    }
}
