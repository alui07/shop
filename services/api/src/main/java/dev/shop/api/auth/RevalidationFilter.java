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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keycloak's SSO session ends on its own schedule, so every {@code revalidateAfter} the API asks Keycloak again. A
 * session Keycloak refuses to refresh ends here (fail closed); if Keycloak cannot be asked the session continues until
 * {@code revalidateGrace} has passed since the last success. One request per session does the work: the others carry on
 * with the authentication they already have.
 */
final class RevalidationFilter extends OncePerRequestFilter {

    private static final String LOCK_KEY = "shop:revalidate:";
    /** Longer than a worst-case refresh plus key fetch, and long enough for Spring Session to commit the result. */
    private static final Duration LOCK_TIME_TO_LIVE = Duration.ofSeconds(20);

    private final Clock clock;
    private final ShopAuthProperties properties;
    private final IdentityRefresher refresher;
    private final StringRedisTemplate redis;
    private final CsrfTokenRepository csrfTokens;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    RevalidationFilter(
            Clock clock,
            ShopAuthProperties properties,
            IdentityRefresher refresher,
            StringRedisTemplate redis,
            CsrfTokenRepository csrfTokens) {
        this.clock = clock;
        this.properties = properties;
        this.refresher = refresher;
        this.redis = redis;
        this.csrfTokens = csrfTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.getContext();
        HttpSession session = request.getSession(false);
        if (context.getAuthentication() instanceof OAuth2AuthenticationToken current
                && session != null
                && isDue(session)
                && Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey(session), "1", LOCK_TIME_TO_LIVE))) {
            revalidate(context, current, session, request, response);
        }
        chain.doFilter(request, response);
    }

    /**
     * The lock is never released: the session is only saved when the request ends, so a request that loaded the old
     * session must still find it held or it would reuse the rotated refresh token. It names the attempt it guards, so
     * the next interval takes a fresh lock.
     */
    private static String lockKey(HttpSession session) {
        Object lastAttempt = session.getAttribute(SessionAttributes.LAST_REVALIDATION_ATTEMPT);
        return LOCK_KEY + session.getId() + ":" + lastAttempt;
    }

    private boolean isDue(HttpSession session) {
        Instant lastAttempt = (Instant) session.getAttribute(SessionAttributes.LAST_REVALIDATION_ATTEMPT);
        return lastAttempt == null || !clock.instant().isBefore(lastAttempt.plus(properties.revalidateAfter()));
    }

    private void revalidate(
            SecurityContext context,
            OAuth2AuthenticationToken current,
            HttpSession session,
            HttpServletRequest request,
            HttpServletResponse response) {
        Instant now = clock.instant();
        session.setAttribute(SessionAttributes.LAST_REVALIDATION_ATTEMPT, now);
        switch (refresher.refresh(current, request, response)) {
            case IdentityRefresher.Refreshed refreshed -> {
                context.setAuthentication(refreshed.authentication());
                contexts.saveContext(context, request, response);
                session.setAttribute(SessionAttributes.LAST_REVALIDATION_SUCCESS, now);
            }
            case IdentityRefresher.Rejected rejected -> endSession(session, request, response);
            case IdentityRefresher.Unavailable unavailable -> {
                Instant lastSuccess = (Instant) session.getAttribute(SessionAttributes.LAST_REVALIDATION_SUCCESS);
                if (lastSuccess == null || !now.isBefore(lastSuccess.plus(properties.revalidateGrace()))) {
                    endSession(session, request, response);
                }
            }
        }
    }

    private void endSession(HttpSession session, HttpServletRequest request, HttpServletResponse response) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        csrfTokens.saveToken(null, request, response);
    }
}
