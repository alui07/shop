package dev.shop.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Caps customer request bodies. A declared length over the limit is refused before anything is read; a body with no
 * declared length (chunked) is counted as it is read and fails at one byte over. Admin bodies keep their own bounds.
 */
final class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final int LIMIT_BYTES = 16 * 1024;

    private static final String API_PREFIX = "/api/";
    private static final String ADMIN_PREFIX = "/api/admin/";

    private final JsonMapper jsonMapper;

    RequestBodyLimitFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(API_PREFIX) || path.startsWith(ADMIN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > LIMIT_BYTES) {
            refuse(response);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Connection", "close");
        ProblemDetail problem = tooLarge();
        response.getWriter()
                .write(jsonMapper.writeValueAsString(Map.of(
                        "type", Problems.BODY_TOO_LARGE,
                        "title", HttpStatus.CONTENT_TOO_LARGE.getReasonPhrase(),
                        "status", HttpStatus.CONTENT_TOO_LARGE.value(),
                        "detail", problem.getDetail(),
                        "errors", List.of())));
    }

    static ProblemDetail tooLarge() {
        return Problems.problem(
                HttpStatus.CONTENT_TOO_LARGE, Problems.BODY_TOO_LARGE, "The request body is too large", List.of());
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private ServletInputStream limited;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedStream(super.getInputStream());
            }
            return limited;
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long consumed;

        LimitedStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) throws IOException {
            consumed += bytes;
            if (consumed > LIMIT_BYTES) {
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
