package dev.shop.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/** Sends the browser home with a flag the SPA turns into a message. Logs the error code, never token contents. */
final class LoginFailureHandler implements AuthenticationFailureHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginFailureHandler.class);

    private final String failureUrl;
    private final RedirectStrategy redirectStrategy;

    LoginFailureHandler(ShopAuthProperties properties, RedirectStrategy redirectStrategy) {
        this.failureUrl = properties.publicHome() + "?login=failed";
        this.redirectStrategy = redirectStrategy;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        LOGGER.info("Sign-in failed: {}", describe(exception));
        HttpSession session = request.getSession(false);
        if (session != null && !SessionAttributes.isSignedIn(session)) {
            session.invalidate();
        }
        redirectStrategy.sendRedirect(request, response, failureUrl);
    }

    private static String describe(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2) {
            return oauth2.getError().getErrorCode();
        }
        return exception.getClass().getSimpleName();
    }
}
