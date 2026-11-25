package dev.shop.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import tools.jackson.databind.json.JsonMapper;

/** Writes the RFC 9457 bodies for failures that happen in filters, before Spring MVC is involved. */
final class ProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String RATE_LIMITED_TYPE = "tag:shop.dev,2026:rate-limited";

    private final JsonMapper jsonMapper;

    ProblemResponses(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        String detail =
                accessDeniedException instanceof CsrfException ? "CSRF token missing or invalid" : "Access is denied";
        write(response, HttpStatus.FORBIDDEN, detail);
    }

    void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        write(response, status, Map.of("type", "about:blank", "detail", detail));
    }

    /** The same body shape as the MVC handlers' 429: a fixed type, a fixed detail and an empty errors array. */
    void writeRateLimited(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        write(
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                Map.of("type", RATE_LIMITED_TYPE, "detail", "Too many requests; try again later", "errors", List.of()));
    }

    private void write(HttpServletResponse response, HttpStatus status, Map<String, Object> fields) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", fields.get("type"));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        fields.forEach(body::putIfAbsent);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(jsonMapper.writeValueAsString(body));
    }
}
