package dev.shop.api.auth;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * What is limited per account. The request matchers are the ones the security chain uses, so a path spelling the chain
 * treats as another route is not counted here either.
 */
enum RateLimitRule {
    CHECKOUT(
            "checkout",
            post(),
            List.of("/api/checkout"),
            new Limit(30, Duration.ofMinutes(1)),
            new Limit(100, Duration.ofHours(1))),
    ORDER_ACTIONS(
            "order-actions",
            post(),
            List.of("/api/orders/{id}/payment", "/api/orders/{id}/cancel"),
            new Limit(30, Duration.ofMinutes(1))),
    CART_WRITES("cart-writes", unsafe(), List.of("/api/cart/**"), new Limit(60, Duration.ofMinutes(1))),
    ADMIN_WRITES(
            "admin-writes",
            unsafe(),
            List.of("/api/admin/**"),
            new Limit(60, Duration.ofMinutes(1)),
            new Limit(600, Duration.ofHours(1)));

    record Limit(long capacity, Duration period) {}

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final PathPatternParser PATTERN_PARSER = new PathPatternParser();

    private final String ruleName;
    private final Predicate<String> methods;
    private final List<String> patterns;
    private final List<Limit> limits;
    private final RequestMatcher matcher;

    RateLimitRule(String ruleName, Predicate<String> methods, List<String> patterns, Limit... limits) {
        this.ruleName = ruleName;
        this.methods = methods;
        this.patterns = patterns;
        this.limits = List.of(limits);
        List<PathPatternRequestMatcher> paths =
                patterns.stream().map(PathPatternRequestMatcher::pathPattern).toList();
        this.matcher =
                request -> methods.test(request.getMethod()) && paths.stream().anyMatch(path -> path.matches(request));
    }

    String ruleName() {
        return ruleName;
    }

    List<Limit> limits() {
        return limits;
    }

    /** Method and route patterns only: safe to log, never the requested path. */
    String describe() {
        return String.join(", ", patterns);
    }

    RequestMatcher matcher() {
        return matcher;
    }

    /** For the OpenAPI contract, whose paths are templates such as {@code /api/cart/lines/{slug}}. */
    boolean appliesTo(String method, String templatePath) {
        return methods.test(method)
                && patterns.stream()
                        .anyMatch(pattern ->
                                PATTERN_PARSER.parse(pattern).matches(PathContainer.parsePath(templatePath)));
    }

    /** Unsafe means anything but GET, HEAD, OPTIONS and TRACE. */
    private static Predicate<String> unsafe() {
        return method -> !SAFE_METHODS.contains(method);
    }

    private static Predicate<String> post() {
        return HttpMethod.POST::matches;
    }
}
