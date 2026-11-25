package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.catalog.SecondApiContext;
import dev.shop.api.support.AdminClient;
import dev.shop.api.support.MutableClock;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.StringUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The limiter's Valkey is paused, not stopped, so its mapped port survives the outage. A command timeout on an
 * established connection fails one request open and nothing more; only a failed connect starts the 5 s skip.
 */
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class RateLimitOutageIT extends RateLimitITSupport {

    private static final String WARNING = "The rate limit store is unavailable";

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private PostgreSQLContainer postgres;

    @Test
    void eachWriteFailsOpenQuicklyWhileAConnectedStoreIsPausedThenTheLimiterResumesAtOnce(CapturedOutput output) {
        AdminClient client = signedInCustomer(newSubject());
        assertThat(putLamp(client).statusCode()).isEqualTo(200);
        assertThat(limiterKeys()).hasSize(1);
        double failuresBefore = failures();
        var docker = limiterValkey.getDockerClient();
        docker.pauseContainerCmd(limiterValkey.getContainerId()).exec();
        boolean paused = true;
        try {
            long started = System.nanoTime();
            HttpResponse<String> whileDown = putLamp(client);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            HttpResponse<String> stillDown = putLamp(client);

            assertThat(whileDown.statusCode()).isEqualTo(200);
            assertThat(stillDown.statusCode()).isEqualTo(200);
            assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
            assertThat(failures()).isEqualTo(failuresBefore + 2);
            assertThat(StringUtils.countOccurrencesOf(output.getAll(), WARNING)).isEqualTo(1);

            docker.unpauseContainerCmd(limiterValkey.getContainerId()).exec();
            paused = false;

            assertThat(putLamp(client).statusCode()).isEqualTo(200);
            assertThat(failures()).as("the limiter works again at once").isEqualTo(failuresBefore + 2);
        } finally {
            if (paused) {
                docker.unpauseContainerCmd(limiterValkey.getContainerId()).exec();
            }
        }
    }

    @Test
    void aStoreThatCannotBeConnectedToIsSkippedForTheBackoffThenUsedAgain() {
        MutableClock backendClock = new MutableClock();
        MeterRegistry limiterMeters = new SimpleMeterRegistry();
        var docker = limiterValkey.getDockerClient();
        ClientResources resources = DefaultClientResources.create();
        LettuceLimiterBackend freshBackend =
                new LettuceLimiterBackend(RateLimitTestConfiguration.detailsOf(limiterValkey), resources, backendClock);
        RateLimiter limiter = new RateLimiter(freshBackend, backendClock, 1, 1, limiterMeters);
        docker.pauseContainerCmd(limiterValkey.getContainerId()).exec();
        boolean paused = true;
        try {
            assertThat(limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1").allowed())
                    .isTrue();
            long started = System.nanoTime();
            assertThat(limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1").allowed())
                    .isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("skipped without another connect attempt")
                    .isLessThan(LettuceLimiterBackend.COMMAND_TIMEOUT.dividedBy(2));

            docker.unpauseContainerCmd(limiterValkey.getContainerId()).exec();
            paused = false;
            assertThat(limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1").allowed())
                    .as("still skipped until the backoff ends")
                    .isTrue();
            assertThat(limiterMeters
                            .get(RateLimiter.BACKEND_FAILURES_METER)
                            .counter()
                            .count())
                    .isEqualTo(3);
            assertThat(limiterKeys()).isEmpty();

            backendClock.advance(LettuceLimiterBackend.BACKOFF);
            assertThat(limiter.tryConsume(RateLimitRule.CHECKOUT, "sub-1").allowed())
                    .isTrue();

            assertThat(limiterMeters
                            .get(RateLimiter.BACKEND_FAILURES_METER)
                            .counter()
                            .count())
                    .as("the store is used again")
                    .isEqualTo(3);
            assertThat(limiterKeys()).hasSize(1);
        } finally {
            if (paused) {
                docker.unpauseContainerCmd(limiterValkey.getContainerId()).exec();
            }
            freshBackend.destroy();
            resources.shutdown(0, 0, TimeUnit.SECONDS);
        }
    }

    @Test
    void aPodStartsAndServesWhileTheStoreIsDown() throws Exception {
        var docker = limiterValkey.getDockerClient();
        docker.pauseContainerCmd(limiterValkey.getContainerId()).exec();
        try (ConfigurableApplicationContext other =
                SecondApiContext.start(postgres, limiterValkey, "maximumSize=10", "--shop.rate-limit.enabled=true")) {
            int port = ((WebServerApplicationContext) other).getWebServer().getPort();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/products"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
        } finally {
            docker.unpauseContainerCmd(limiterValkey.getContainerId()).exec();
        }
    }

    private HttpResponse<String> putLamp(AdminClient client) {
        return client.put("/api/cart/lines/" + LAMP, "{\"quantity\":1}");
    }

    private double failures() {
        return meters.get(RateLimiter.BACKEND_FAILURES_METER).counter().count();
    }
}
