package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class ApiRequestFilterTest {

    private final ApiRequestFilter filter = new ApiRequestFilter();

    @Test
    void addsNosniffAndLogsMethodPathStatusAndDuration(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(204);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/ping"), response, new MockFilterChain());

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(output).containsPattern("GET /api/ping 204 \\d+ ms");
    }

    @Test
    void stillLogsAndKeepsTheHeaderWhenTheChainFails(CapturedOutput output) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse res)
                    throws ServletException {
                throw new ServletException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("POST", "/api/fail"), response, failing))
                .isInstanceOf(ServletException.class);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(output).contains("POST /api/fail");
    }
}
