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
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keycloak's SSO session ends on its own schedule, so every {@code revalidateAfter} the API asks Keycloak again. A
 * session Keycloak refuses to refresh ends here (fail closed); if Keycloak cannot be asked the session continues until
 * {@code revalidateGrace} has passed since the last success. One request per session does the work: the others carry on
 * with the authentication they already have.
 */
final class RevalidationFilter extends OncePerRequestFilter {

    private static final RequestMatcher ADMIN_PATHS =
            PathPatternRequestMatcher.withDefaults().matcher("/api/admin/**");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final String LOCK_KEY = "shop:revalidate:";
    /** Longer than a worst-case refresh plus key fetch, and long enough for Spring Session to commit the result. */
    private static final Duration LOCK_TIME_TO_LIVE = Duration.ofSeconds(20);

    private final Clock clock;
    private final ShopAuthProperties properties;
    private final IdentityRefresher refresher;
    private final StringRedisTemplate redis;
    private final CsrfTokenRepository csrfTokens;
    private final ProblemResponses problems;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    RevalidationFilter(
            Clock clock,
            ShopAuthProperties properties,
            IdentityRefresher refresher,
            StringRedisTemplate redis,
            CsrfTokenRepository csrfTokens,
            ProblemResponses problems) {
        this.clock = clock;
        this.properties = properties;
        this.refresher = refresher;
        this.redis = redis;
        this.csrfTokens = csrfTokens;
        this.problems = problems;
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
        if (isAdminWriteWithoutFreshConfirmation(request)) {
            response.setHeader(
                    HttpHeaders.RETRY_AFTER,
                    String.valueOf(properties.revalidateAfter().toSeconds()));
            problems.write(response, HttpStatus.SERVICE_UNAVAILABLE, "Your access cannot be confirmed right now");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Reads ride out a Keycloak outage for the whole grace period; an admin write does not: it needs a confirmation
     * (login or a successful refresh) no older than {@code revalidateAfter}. A session ended above has no
     * authentication left, so it falls through to the 401.
     */
    private boolean isAdminWriteWithoutFreshConfirmation(HttpServletRequest request) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2AuthenticationToken)
                || SAFE_METHODS.contains(request.getMethod())
                || !ADMIN_PATHS.matches(request)) {
            return false;
        }
        HttpSession session = request.getSession(false);
        Instant lastSuccess =
                session == null ? null : (Instant) session.getAttribute(SessionAttributes.LAST_REVALIDATION_SUCCESS);
        return lastSuccess == null || !lastSuccess.isAfter(clock.instant().minus(properties.revalidateAfter()));
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
