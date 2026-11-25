package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RequestBodyLimitFilterTest {

    private static final int LIMIT = RequestBodyLimitFilter.LIMIT_BYTES;

    private final RequestBodyLimitFilter filter =
            new RequestBodyLimitFilter(JsonMapper.builder().build());

    /** A request that does not declare its length, like a chunked one. */
    private static MockHttpServletRequest streamed(String path, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[bytes]);
        return request;
    }

    private ServletInputStream limitedStream(MockHttpServletRequest request) throws Exception {
        AtomicReference<ServletInputStream> stream = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> stream.set(req.getInputStream()));
        return stream.get();
    }

    @Test
    void aBodyOfExactlyTheLimitReadsByteByByteAndInBulk() throws Exception {
        ServletInputStream single = limitedStream(streamed("/api/x", LIMIT));
        int total = 0;
        while (single.read() >= 0) {
            total++;
        }
        assertThat(total).isEqualTo(LIMIT);
        assertThat(single.isFinished()).isTrue();

        ServletInputStream bulk = limitedStream(streamed("/api/x", LIMIT));
        byte[] buffer = new byte[4096];
        int bulkTotal = 0;
        for (int read = bulk.read(buffer, 0, buffer.length); read >= 0; read = bulk.read(buffer, 0, buffer.length)) {
            bulkTotal += read;
        }
        assertThat(bulkTotal).isEqualTo(LIMIT);
    }

    @Test
    void theByteAfterTheLimitFailsWhetherReadSinglyOrInBulk() throws Exception {
        ServletInputStream single = limitedStream(streamed("/api/x", LIMIT + 1));
        for (int i = 0; i < LIMIT; i++) {
            single.read();
        }
        assertThatThrownBy(single::read).isInstanceOf(BodyTooLargeException.class);

        ServletInputStream bulk = limitedStream(streamed("/api/x", LIMIT + 1));
        assertThatThrownBy(() -> bulk.read(new byte[LIMIT + 1], 0, LIMIT + 1))
                .isInstanceOf(BodyTooLargeException.class);
    }

    @Test
    void theStreamIsCreatedOnceAndPassesTheRestOfTheContractThrough() throws Exception {
        MockHttpServletRequest request = streamed("/api/x", 3);
        AtomicReference<HttpServletRequest> wrapped = new AtomicReference<>();
        AtomicReference<ServletInputStream> stream = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            wrapped.set((HttpServletRequest) req);
            stream.set(req.getInputStream());
        });

        assertThat(wrapped.get().getInputStream()).isSameAs(stream.get());
        assertThat(stream.get().isReady()).isTrue();
        assertThat(stream.get().isFinished()).isFalse();
    }

    @Test
    void theReadListenerGoesToTheUnderlyingStream() throws Exception {
        ServletInputStream underlying = mock(ServletInputStream.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x") {
            @Override
            public ServletInputStream getInputStream() {
                return underlying;
            }
        };
        ReadListener listener = new NoOpListener();

        ServletInputStream limited = limitedStream(request);
        limited.setReadListener(listener);

        verify(underlying).setReadListener(listener);
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedWithoutCallingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/cart/lines/x");
        request.setContent(new byte[LIMIT + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> called = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("tag:shop.dev,2026:body-too-large");
    }

    @Test
    void pathsOutsideTheCustomerApiAreNotLimited() throws Exception {
        for (String path : new String[] {"/logout", "/actuator/health"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setContent(new byte[LIMIT * 2]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

            filter.doFilter(request, response, (req, res) -> seen.set((HttpServletRequest) req));

            assertThat(seen.get()).as(path).isSameAs(request);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void adminBodiesHaveTheirOwnLargerLimitDeclaredOrStreamed() throws Exception {
        int adminLimit = RequestBodyLimitFilter.ADMIN_LIMIT_BYTES;
        assertThat(adminLimit).isGreaterThan(LIMIT);

        MockHttpServletRequest declared = new MockHttpServletRequest("POST", "/api/admin/products");
        declared.setContentType("application/json");
        declared.setContent(new byte[adminLimit + 1]);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(declared, refused, (req, res) -> {
            throw new AssertionError("the request must be refused");
        });
        assertThat(refused.getStatus()).isEqualTo(413);

        MockHttpServletRequest between = streamed("/api/admin/products", LIMIT + 1);
        between.setContentType("application/json");
        ServletInputStream accepted = limitedStream(between);
        assertThat(accepted.readNBytes(LIMIT + 1)).hasSize(LIMIT + 1);

        MockHttpServletRequest over = streamed("/api/admin/products", adminLimit + 1);
        over.setContentType("application/json");
        ServletInputStream tooLarge = limitedStream(over);
        assertThatThrownBy(() -> tooLarge.readNBytes(adminLimit + 1)).isInstanceOf(BodyTooLargeException.class);
    }

    @Test
    void anAdminBodyThatIsNotJsonIsRefusedLikeAnyOther() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/products");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(new byte[] {'a'});
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("the request must be refused");
        });

        assertThat(response.getStatus()).isEqualTo(415);
    }

    private static final class NoOpListener implements ReadListener {
        @Override
        public void onDataAvailable() throws IOException {}

        @Override
        public void onAllDataRead() throws IOException {}

        @Override
        public void onError(Throwable throwable) {}
    }

    @Test
    void onlyApplicationJsonAndApplicationPlusJsonBodiesPass() throws Exception {
        for (String type : new String[] {"text/x+json", "application/xml", "application/x-www-form-urlencoded"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x");
            request.setContentType(type);
            request.setContent(new byte[] {'a'});
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> {
                throw new AssertionError("the request must be refused");
            });

            assertThat(response.getStatus()).as(type).isEqualTo(415);
            assertThat(response.getContentAsString()).doesNotContain(type);
        }
    }
}
