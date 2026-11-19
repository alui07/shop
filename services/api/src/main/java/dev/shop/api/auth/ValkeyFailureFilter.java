package dev.shop.api.auth;

import io.lettuce.core.RedisCommandTimeoutException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before the session filter. A request without a session cookie never touches Valkey, so the public catalog keeps
 * serving; a request that needs the session store while it is down gets a 503 instead of a 500.
 */
final class ValkeyFailureFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ValkeyFailureFilter.class);

    private final ProblemResponses problems;

    ValkeyFailureFilter(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException e) {
            if (!isValkeyFailure(e)) {
                throw e;
            }
            LOGGER.warn("The session store is unavailable: {}", rootMessage(e));
            response.reset();
            problems.write(response, HttpStatus.SERVICE_UNAVAILABLE, "The session store is temporarily unavailable");
        }
    }

    /** A query timeout is Valkey's only when Lettuce raised it; a JDBC one must not be reported as a session store outage. */
    private static boolean isValkeyFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisConnectionFailureException || cause instanceof RedisCommandTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
