package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class ValkeyFailureFilterTest {

    private final ValkeyFailureFilter filter =
            new ValkeyFailureFilter(new ProblemResponses(JsonMapper.builder().build()));

    @Test
    void aConnectionFailureBecomesA503Problem() throws Exception {
        MockHttpServletResponse response = run(new RedisConnectionFailureException("down"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":503");
    }

    @Test
    void aCommandTimeoutBecomesA503ProblemEvenWhenWrappedByTheServlet() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse res)
                    throws ServletException {
                throw new ServletException("Request processing failed", new QueryTimeoutException("slow"));
            }
        };

        filter.doFilter(new MockHttpServletRequest(), response, failing);

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void otherFailuresAreNotHidden() {
        assertThatThrownBy(() -> run(new IllegalStateException("boom"))).isInstanceOf(IllegalStateException.class);
    }

    private MockHttpServletResponse run(RuntimeException failure) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse res) {
                throw failure;
            }
        };
        filter.doFilter(new MockHttpServletRequest(), response, failing);
        return response;
    }
}
