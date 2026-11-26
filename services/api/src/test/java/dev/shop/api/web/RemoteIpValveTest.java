package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.boot.tomcat.autoconfigure.TomcatWebServerFactoryCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves the forwarding trust configured for the kind cluster: only the pod network is a trusted proxy, so the real
 * client is the entry Envoy appended, and a client-sent entry to its left is never believed.
 */
class RemoteIpValveTest {

    private static final String POD_NETWORK = "10.244.0.0/16";

    private static RemoteIpValve valve(Map<String, Object> properties) {
        Binder binder = new Binder(new MapConfigurationPropertySource(properties));
        ServerProperties server = binder.bind("server", ServerProperties.class).orElseGet(ServerProperties::new);
        TomcatServerProperties tomcat =
                binder.bind("server.tomcat", TomcatServerProperties.class).orElseGet(TomcatServerProperties::new);
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        new TomcatWebServerFactoryCustomizer(new MockEnvironment(), server, tomcat, new WebProperties())
                .customize(factory);
        return factory.getEngineValves().stream()
                .filter(RemoteIpValve.class::isInstance)
                .map(RemoteIpValve.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static String clientSeenBy(RemoteIpValve valve, String peer, String forwardedFor) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        valve.setNext(new ValveBase() {
            @Override
            public void invoke(Request request, Response response) {
                seen.set(request.getRemoteAddr());
            }
        });
        org.apache.coyote.Request coyote = new org.apache.coyote.Request();
        Request request = new Request(new Connector(), coyote);
        coyote.remoteAddr().setString(peer);
        if (forwardedFor != null) {
            coyote.getMimeHeaders().addValue("X-Forwarded-For").setString(forwardedFor);
        }
        valve.invoke(request, new Response(new org.apache.coyote.Response()));
        return seen.get();
    }

    private static Map<String, Object> kind() {
        return Map.of(
                "server.forward-headers-strategy", "native", "server.tomcat.remoteip.internal-proxies", POD_NETWORK);
    }

    @Test
    void aClientSentEntryLeftOfTheOneEnvoyAppendedIsNotTheClient() throws Exception {
        String client = clientSeenBy(valve(kind()), "10.244.0.9", "1.2.3.4, 172.18.0.5");

        assertThat(client).isEqualTo("172.18.0.5");
    }

    @Test
    void aDirectCallerOutsideThePodNetworkCannotChooseItsAddress() throws Exception {
        assertThat(clientSeenBy(valve(kind()), "127.0.0.1", "1.2.3.4")).isEqualTo("127.0.0.1");
        assertThat(clientSeenBy(valve(kind()), "172.18.0.5", "1.2.3.4")).isEqualTo("172.18.0.5");
    }

    @Test
    void withoutAForwardedHeaderThePeerIsTheClient() throws Exception {
        assertThat(clientSeenBy(valve(kind()), "10.244.0.9", null)).isEqualTo("10.244.0.9");
    }

    @Test
    void bootsDefaultTrustsPrivateRangesWhichIsWhyTheClusterNarrowsIt() throws Exception {
        RemoteIpValve defaults = valve(Map.of("server.forward-headers-strategy", "native"));

        assertThat(clientSeenBy(defaults, "172.18.0.5", "1.2.3.4")).isEqualTo("1.2.3.4");
    }
}
