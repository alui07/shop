package dev.shop.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** The session that carries state and PKCE verifier across the login redirect is the only anonymous session; it is short. */
final class LoginAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> delegate =
            new HttpSessionOAuth2AuthorizationRequestRepository();
    private final int sessionSeconds;

    LoginAuthorizationRequestRepository(Duration loginSessionTimeout) {
        this.sessionSeconds = (int) loginSessionTimeout.toSeconds();
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return delegate.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request, HttpServletResponse response) {
        delegate.saveAuthorizationRequest(authorizationRequest, request, response);
        HttpSession session = request.getSession();
        if (!SessionAttributes.isSignedIn(session)) {
            session.setMaxInactiveInterval(sessionSeconds);
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        return delegate.removeAuthorizationRequest(request, response);
    }
}
