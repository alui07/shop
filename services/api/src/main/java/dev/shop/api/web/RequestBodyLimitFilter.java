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
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Caps customer request bodies. A declared length over the limit is refused before anything is read; a body with no
 * declared length (chunked) is counted as it is read and fails at one byte over. A body that is not JSON is refused
 * with 415 before it is read. Admin bodies get a larger cap, and the payment webhook, which enforces its own larger
 * limit, is skipped by exact method and path: any other spelling of the path stays under this cap.
 */
final class RequestBodyLimitFilter extends OncePerRequestFilter {

    static final int LIMIT_BYTES = 16 * 1024;
    static final int ADMIN_LIMIT_BYTES = 32 * 1024;

    private static final String API_PREFIX = "/api/";
    private static final String ADMIN_PREFIX = "/api/admin/";
    private static final String WEBHOOK_PATH = "/api/payments/webhook";

    private final JsonMapper jsonMapper;

    RequestBodyLimitFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(API_PREFIX)
                || (HttpMethod.POST.matches(request.getMethod()) && WEBHOOK_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit = limitFor(request);
        if (request.getContentLengthLong() > limit) {
            refuse(response, tooLarge());
            return;
        }
        if (hasBody(request) && !isJson(request.getContentType())) {
            refuse(response, unsupportedMediaType());
            return;
        }
        chain.doFilter(new LimitedRequest(request, limit), response);
    }

    private static int limitFor(HttpServletRequest request) {
        return request.getRequestURI().startsWith(ADMIN_PREFIX) ? ADMIN_LIMIT_BYTES : LIMIT_BYTES;
    }

    /** Form bodies would otherwise be parsed by the servlet container, outside the counted stream. */
    private static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type)
                    || ("application".equalsIgnoreCase(type.getType())
                            && type.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json"));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private void refuse(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Connection", "close");
        response.getWriter()
                .write(jsonMapper.writeValueAsString(Map.of(
                        "type", String.valueOf(problem.getType()),
                        "title", HttpStatus.valueOf(problem.getStatus()).getReasonPhrase(),
                        "status", problem.getStatus(),
                        "detail", String.valueOf(problem.getDetail()),
                        "errors", List.of())));
    }

    private static ProblemDetail unsupportedMediaType() {
        return Problems.problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                Problems.UNSUPPORTED_MEDIA_TYPE,
                "The request body must be application/json",
                List.of());
    }

    static ProblemDetail tooLarge() {
        return Problems.problem(
                HttpStatus.CONTENT_TOO_LARGE, Problems.BODY_TOO_LARGE, "The request body is too large", List.of());
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final int limit;
        private ServletInputStream limited;

        LimitedRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedStream(super.getInputStream(), limit);
            }
            return limited;
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final int limit;
        private long consumed;

        LimitedStream(ServletInputStream delegate, int limit) {
            this.delegate = delegate;
            this.limit = limit;
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
            if (consumed > limit) {
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
