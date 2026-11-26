package dev.shop.api;

import static dev.shop.api.support.HttpGet.get;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.IntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
class OpenApiContractIT {

    private static final Path COMMITTED_SPEC = Path.of("openapi", "openapi.json");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @LocalServerPort
    private int port;

    @Test
    void liveDocumentMatchesTheCommittedSpecOrRewritesItWhenAsked() throws IOException {
        ObjectNode live = liveDocument();

        if (Boolean.parseBoolean(System.getenv("UPDATE_OPENAPI"))) {
            Files.writeString(COMMITTED_SPEC, sortedPrettyJson(live));
            return;
        }

        JsonNode committed = JSON.readTree(Files.readString(COMMITTED_SPEC));
        assertThat(live.equals(committed))
                .as("services/api/openapi/openapi.json is stale; run `make api-spec` and commit the result")
                .isTrue();
    }

    @Test
    void everyPropertyOfTheMainSchemasIsRequired() {
        JsonNode schemas = liveDocument().get("components").get("schemas");

        for (String name : new String[] {
            "Money",
            "ProductSummary",
            "ProductPage",
            "ProductDetail",
            "Category",
            "Cart",
            "CartLine",
            "CartLineInput",
            "CheckoutInput",
            "Order",
            "OrderLine",
            "OrderSummary",
            "OrderPage",
            "PaymentStart",
            "FakePspPayment",
            "FakePspCompletion",
            "WebhookEvent",
            "WebhookEventData",
            "WebhookAck"
        }) {
            JsonNode schema = schemas.get(name);
            assertThat(schema.get("required").valueStream().map(JsonNode::asString))
                    .as(name)
                    .containsExactlyInAnyOrderElementsOf(
                            schema.get("properties").propertyNames());
        }
    }

    @Test
    void theLoadtestSessionEndpointIsNeverDocumented() {
        assertThat(liveDocument().get("paths").propertyNames()).noneMatch(path -> path.startsWith("/api/loadtest"));
    }

    @Test
    void exactlyTheRateLimitedOperationsDeclareTheTooManyRequestsProblem() {
        JsonNode paths = liveDocument().get("paths");
        java.util.Set<String> limited = new java.util.TreeSet<>();
        for (String path : paths.propertyNames()) {
            for (String method : paths.get(path).propertyNames()) {
                JsonNode response = paths.get(path).get(method).get("responses").get("429");
                if (response != null) {
                    limited.add(method.toUpperCase() + " " + path);
                    assertThat(response.get("content")
                                    .get("application/problem+json")
                                    .get("schema")
                                    .get("$ref")
                                    .asString())
                            .isEqualTo("#/components/schemas/ProblemDetail");
                }
            }
        }

        assertThat(limited)
                .contains(
                        "POST /api/checkout",
                        "POST /api/orders/{id}/payment",
                        "POST /api/orders/{id}/cancel",
                        "PUT /api/cart/lines/{slug}",
                        "DELETE /api/cart/lines/{slug}",
                        "POST /api/admin/products")
                .noneMatch(operation -> operation.startsWith("GET "));
    }

    private ObjectNode liveDocument() {
        ObjectNode document =
                (ObjectNode) JSON.readTree(get(port, "/v3/api-docs").body());
        document.remove("servers");
        return document;
    }

    private static String sortedPrettyJson(ObjectNode document) {
        return JSON.writeValueAsString(JSON.convertValue(document, Object.class)) + "\n";
    }
}
