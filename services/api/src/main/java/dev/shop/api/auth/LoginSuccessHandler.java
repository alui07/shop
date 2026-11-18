package dev.shop.api.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;

/** Runs after the session id was rotated: stamps the session and lifts it from the short pre-login lifetime. */
final class LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final Clock clock;
    private final ShopAuthProperties properties;

    LoginSuccessHandler(Clock clock, ShopAuthProperties properties) {
        super(properties.publicHome());
        setAlwaysUseDefaultTargetUrl(true);
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        HttpSession session = request.getSession();
        Instant now = clock.instant();
        session.setAttribute(SessionAttributes.AUTHENTICATED_AT, now);
        session.setAttribute(SessionAttributes.LAST_REVALIDATION_ATTEMPT, now);
        session.setAttribute(SessionAttributes.LAST_REVALIDATION_SUCCESS, now);
        session.setMaxInactiveInterval((int) properties.idleSessionTimeout().toSeconds());
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
