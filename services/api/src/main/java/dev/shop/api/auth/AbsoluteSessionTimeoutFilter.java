package dev.shop.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a signed-in session a fixed time after login however active it is. The expiring request itself runs
 * anonymously, and a session that was never stamped counts as expired.
 */
final class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final Duration timeout;
    private final CsrfTokenRepository csrfTokenRepository;

    AbsoluteSessionTimeoutFilter(Clock clock, Duration timeout, CsrfTokenRepository csrfTokenRepository) {
        this.clock = clock;
        this.timeout = timeout;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isSignedIn(SecurityContextHolder.getContext().getAuthentication())
                && isExpired(request.getSession(false))) {
            endSession(request, response);
        }
        chain.doFilter(request, response);
    }

    private static boolean isSignedIn(Authentication authentication) {
        return authentication != null && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private boolean isExpired(HttpSession session) {
        if (session == null) {
            return true;
        }
        Object authenticatedAt = session.getAttribute(SessionAttributes.AUTHENTICATED_AT);
        return !(authenticatedAt instanceof Instant instant) || !clock.instant().isBefore(instant.plus(timeout));
    }

    private void endSession(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
    }
}
