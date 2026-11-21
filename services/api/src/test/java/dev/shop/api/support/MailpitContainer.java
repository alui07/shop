package dev.shop.api.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Mailpit with the few calls the tests need: read the mailbox of one recipient, clear it, freeze and thaw the server. */
public final class MailpitContainer extends GenericContainer<MailpitContainer> {

    private static final int SMTP_PORT = 1025;
    private static final int API_PORT = 8025;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private volatile boolean frozen;

    MailpitContainer(String image) {
        super(DockerImageName.parse(image));
        withExposedPorts(SMTP_PORT, API_PORT);
        waitingFor(Wait.forHttp("/livez").forPort(API_PORT));
    }

    public int smtpPort() {
        return getMappedPort(SMTP_PORT);
    }

    /** The messages addressed to this recipient, newest first. */
    public JsonNode messagesTo(String address) {
        return JSON.readTree(get("/api/v1/search?query="
                        + java.net.URLEncoder.encode("to:" + address, java.nio.charset.StandardCharsets.UTF_8)))
                .get("messages");
    }

    public HttpResponse<String> message(String id) {
        try {
            return HTTP.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://" + getHost() + ":" + getMappedPort(API_PORT) + "/api/v1/message/" + id))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("Mailpit is not reachable", e);
        }
    }

    /** A hung mail server: connections are accepted by the kernel and never answered. */
    public void freeze() {
        getDockerClient().pauseContainerCmd(getContainerId()).exec();
        frozen = true;
    }

    /** Safe to call when the server is running. */
    public void thaw() {
        if (frozen) {
            getDockerClient().unpauseContainerCmd(getContainerId()).exec();
            frozen = false;
        }
    }

    private String get(String path) {
        try {
            return HTTP.send(
                            HttpRequest.newBuilder(
                                            URI.create("http://" + getHost() + ":" + getMappedPort(API_PORT) + path))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("Mailpit is not reachable", e);
        }
    }
}
