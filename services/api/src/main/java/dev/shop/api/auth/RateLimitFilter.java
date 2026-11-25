package dev.shop.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts authorised, signed-in requests per account. It sits after the authorisation filter, so a request that was
 * refused (no session, no CSRF token, wrong role) never takes a token or creates a bucket.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final RateLimiter limiter;
    private final ProblemResponses problems;

    RateLimitFilter(RateLimitProperties properties, RateLimiter limiter, ProblemResponses problems) {
        this.properties = properties;
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> subject = properties.enabled()
                ? subjectOf(SecurityContextHolder.getContext().getAuthentication())
                : Optional.empty();
        Optional<RateLimitRule> rule = subject.isPresent() ? matchingRule(request) : Optional.empty();
        if (rule.isPresent()) {
            RateLimiter.Decision decision = limiter.tryConsume(rule.get(), subject.get());
            if (!decision.allowed()) {
                problems.writeRateLimited(response, decision.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static Optional<RateLimitRule> matchingRule(HttpServletRequest request) {
        return Arrays.stream(RateLimitRule.values())
                .filter(rule -> rule.matcher().matches(request))
                .findFirst();
    }

    private static Optional<String> subjectOf(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof OidcUser user
                && user.getSubject() != null
                && !user.getSubject().isBlank()) {
            return Optional.of(user.getSubject());
        }
        return Optional.empty();
    }
}
