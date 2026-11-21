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
    void logsMethodPathStatusAndDuration(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(204);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/ping"), response, new MockFilterChain());

        assertThat(output).containsPattern("GET /api/ping 204 \\d+ ms");
    }

    @Test
    void stillLogsWhenTheChainFails(CapturedOutput output) {
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

        assertThat(output).contains("POST /api/fail");
    }

    @Test
    void theTokenOfTheFakePaymentPageNeverReachesTheLog(CapturedOutput output) throws Exception {
        for (String path : new String[] {
            "/api/fake-psp/payments/secret-token",
            "/api/fake-psp/payments/secret-token/complete",
            "/api/fake-psp/payments/secret-token/",
            "/api/fake-psp/%70ayments/secret-token",
            "/api/fake-psp/%70ayments/%73ecret-token/complete",
            "/api/fake-psp/payments;a=b/secret-token",
            "/api/fake-psp/payments/secret-token;jsessionid=x",
            "/api/fake-psp//payments//secret-token",
            "/api/fake-psp/./payments/secret-token",
            "/api/fake-psp/other/../payments/secret-token"
        }) {
            filter.doFilter(
                    new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), new MockFilterChain());
        }

        assertThat(output.getAll()).doesNotContain("ecret");
        assertThat(output)
                .contains("GET /api/fake-psp/payments/*** 200")
                .contains("GET /api/fake-psp/payments/***/complete 200")
                .contains("GET /api/fake-psp/payments/***/ 200");
    }

    @Test
    void aPathThatCannotBeDecodedIsNotLoggedAtAll(CapturedOutput output) throws Exception {
        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/fake-psp/%70ayments/secret-token%zz"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(output.getAll()).doesNotContain("ecret").contains("GET [undecodable path] 200");
    }
}
