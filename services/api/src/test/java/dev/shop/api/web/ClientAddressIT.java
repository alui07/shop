package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * With only the pod network trusted as a proxy, a caller from anywhere else cannot choose the address the application
 * sees by sending X-Forwarded-For. The test sends from loopback, which is outside {@code 10.244.0.0/16}.
 */
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=10.244.0.0/16")
@Import(ClientAddressIT.RemoteAddressController.class)
class ClientAddressIT extends AuthIT {

    @RestController
    static class RemoteAddressController {

        /** Under /api/products so the security chain lets an anonymous request through. */
        @GetMapping("/api/products/remote-address")
        String remoteAddress(HttpServletRequest request) {
            return request.getRemoteAddr();
        }
    }

    @Test
    void aForwardedHeaderFromAnUntrustedPeerIsIgnored() {
        var plain = browser().get("/api/products/remote-address");
        var spoofed = browser().get("/api/products/remote-address", "X-Forwarded-For", "1.2.3.4, 5.6.7.8");

        assertThat(plain.statusCode()).isEqualTo(200);
        assertThat(spoofed.body())
                .isEqualTo(plain.body())
                .doesNotContain("1.2.3.4")
                .doesNotContain("5.6.7.8");
    }
}
