package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.ShopIT;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** A request value that cannot be converted is never quoted back in the error. */
class ProblemDetailEchoIT extends ShopIT {

    private static final String PAYLOAD = "zq9-not-a-number";

    static Stream<Arguments> unconvertibleInputs() {
        return Stream.of(
                Arguments.of("GET", "/api/products?page=" + PAYLOAD, null, "page"),
                Arguments.of("GET", "/api/orders?page=" + PAYLOAD, null, "page"),
                Arguments.of("GET", "/api/orders/" + PAYLOAD, null, "id"),
                Arguments.of("POST", "/api/orders/" + PAYLOAD + "/cancel", null, "id"),
                Arguments.of("POST", "/api/checkout", PAYLOAD, "Idempotency-Key"),
                Arguments.of("POST", "/api/cart/lines/desk-lamp/additions", PAYLOAD, "Idempotency-Key"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unconvertibleInputs")
    void theRejectedValueIsNotInTheResponse(String method, String path, String idempotencyKey, String parameter) {
        AdminClient client = customer("echo-" + UUID.randomUUID());
        String[] headers = idempotencyKey == null ? new String[0] : new String[] {"Idempotency-Key", idempotencyKey};

        HttpResponse<String> response = method.equals("GET")
                ? client.get(path)
                : client.post(
                        path,
                        path.endsWith("/additions")
                                ? "{\"quantity\":1}"
                                : "{\"cartVersion\":0,\"expectedTotalMinor\":0}",
                        headers);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain(PAYLOAD);
        JsonNode problem = AdminClient.json(response);
        assertThat(problem.get("type").asString()).isEqualTo(Problems.VALIDATION);
        assertThat(problem.get("detail").asString()).isEqualTo("The request is not valid");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo(parameter);
        assertThat(problem.get("errors").get(0).get("message").asString()).isEqualTo("has the wrong type");
    }

    @org.junit.jupiter.api.Test
    void aBadCatalogueQueryDoesNotEchoEither() {
        HttpResponse<String> response = browser().get("/api/products?page=" + PAYLOAD);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain(PAYLOAD);
    }
}
