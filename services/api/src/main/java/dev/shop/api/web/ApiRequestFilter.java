package dev.shop.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/** Logs one line per request. */
@Component
class ApiRequestFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiRequestFilter.class);
    private static final String FAKE_PSP_PREFIX = "/api/fake-psp/payments/";
    private static final String UNDECODABLE_PATH = "[undecodable path]";
    private static final Pattern PATH_PARAMETERS = Pattern.compile(";[^/]*");
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long millis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            LOGGER.info(
                    "{} {} {} {} ms",
                    request.getMethod(),
                    masked(request.getRequestURI()),
                    response.getStatus(),
                    millis);
        }
    }

    /**
     * The segment after the prefix is a capability token for the fake payment page; it never reaches a log. Matching is
     * on the path the way Spring routes it: path parameters dropped, percent-escapes decoded, slashes and dot segments
     * collapsed. A path that cannot be decoded is not logged at all.
     */
    private static String masked(String uri) {
        String path = routedPath(uri);
        if (path == null) {
            return UNDECODABLE_PATH;
        }
        if (!path.startsWith(FAKE_PSP_PREFIX)) {
            return uri;
        }
        int end = path.indexOf('/', FAKE_PSP_PREFIX.length());
        return FAKE_PSP_PREFIX + "***" + (end < 0 ? "" : path.substring(end));
    }

    private static String routedPath(String uri) {
        try {
            String decoded = UriUtils.decode(PATH_PARAMETERS.matcher(uri).replaceAll(""), StandardCharsets.UTF_8);
            return StringUtils.cleanPath(REPEATED_SLASHES.matcher(decoded).replaceAll("/"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
