package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.ShopIT;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class OrderEndpointsIT extends ShopIT {

    @Test
    void theListShowsOnlyTheOwnOrdersNewestFirstWithAnItemCount() {
        AdminClient client = customer("lister");
        JsonNode older = placeOrderFor(client, LAMP, 2);
        putLine(client, LAMP, 1);
        putLine(client, NOTEBOOK, 3);
        JsonNode newer = placeOrder(client);
        placeOrderFor(customer("someone-else"), LAMP, 1);

        HttpResponse<String> response = client.get("/api/orders");
        JsonNode page = ok(response);

        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(page.get("totalItems").asLong()).isEqualTo(2);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("items").valueStream().map(item -> item.get("id").asString()))
                .containsExactly(newer.get("id").asString(), older.get("id").asString());
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(first.get("itemCount").asInt()).isEqualTo(4);
        assertThat(first.get("total").get("amountMinor").asLong()).isEqualTo(LAMP_PRICE + 3 * NOTEBOOK_PRICE);
        assertThat(first.has("expiresAt")).isTrue();
    }

    @Test
    void theListPages() {
        AdminClient client = customer("pager");
        for (int i = 0; i < 3; i++) {
            placeOrderFor(client, NOTEBOOK, 1);
            clock.advance(Duration.ofSeconds(1));
        }

        JsonNode second = ok(client.get("/api/orders?page=1&size=2"));

        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("totalItems").asLong()).isEqualTo(3);
        assertThat(second.get("totalPages").asInt()).isEqualTo(2);
        assertThat(ok(client.get("/api/orders?page=5&size=2")).get("items")).isEmpty();
        assertThat(ok(customer("no-orders").get("/api/orders"))
                        .get("totalPages")
                        .asInt())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=0", "size=51", "page=-1", "page=10001", "page=abc", "size=1.5"})
    void theListRefusesBadPagingParameters(String query) {
        assertThat(customer("pager-bad").get("/api/orders?" + query).statusCode())
                .isEqualTo(400);
    }

    @Test
    void anOrderIsVisibleOnlyToItsOwner() {
        JsonNode order = placeOrderFor(customer("owner"), LAMP, 1);
        String path = "/api/orders/" + order.get("id").asString();
        AdminClient other = customer("intruder");

        assertProblem(other.get(path), 404, "about:blank");
        assertProblem(other.post(path + "/cancel", null), 404, "about:blank");

        assertThat(orderStatus(order.get("id").asString())).isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(lampId)).isEqualTo(1);
        assertThat(customer("owner").get(path).statusCode()).isEqualTo(200);
    }

    @Test
    void anUnknownOrderIsNotFoundAndAMalformedIdIsABadRequest() {
        AdminClient client = customer("seeker");

        assertProblem(client.get("/api/orders/" + UUID.randomUUID()), 404, "about:blank");
        assertProblem(client.post("/api/orders/" + UUID.randomUUID() + "/cancel", null), 404, "about:blank");
        assertThat(client.get("/api/orders/not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(client.post("/api/orders/not-a-uuid/cancel", null).statusCode())
                .isEqualTo(400);
    }

    @Test
    void cancellingReleasesTheStockAndASecondCancelIsAConflict() {
        AdminClient client = customer("canceller");
        placeOrderFor(client, LAMP, 3);
        JsonNode order = placeOrderFor(client, NOTEBOOK, 2);
        String path = "/api/orders/" + order.get("id").asString() + "/cancel";

        JsonNode cancelled = ok(client.post(path, null));

        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("lines")).hasSize(1);
        assertThat(reserved(notebookId)).isZero();
        assertThat(reserved(lampId)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE product_id = :id")
                        .param("id", notebookId)
                        .query(String.class)
                        .single())
                .isEqualTo("RELEASED");
        assertStockInvariant();

        assertProblem(client.post(path, null), 409, type("order-state"));
        assertThat(reserved(lampId)).isEqualTo(3);
        assertThat(ok(client.get("/api/orders/" + order.get("id").asString()))
                        .get("status")
                        .asString())
                .isEqualTo("CANCELLED");
    }

    @Test
    void cancellingAnOrderPastItsHoldReleasesItAndAnswersConflict() {
        AdminClient client = customer("late-canceller");
        JsonNode order = placeOrderFor(client, LAMP, 4);
        clock.advance(Duration.ofMinutes(16));

        assertProblem(
                client.post("/api/orders/" + order.get("id").asString() + "/cancel", null), 409, type("order-state"));

        assertThat(orderStatus(order.get("id").asString())).isEqualTo("EXPIRED");
        assertThat(reserved(lampId)).isZero();
        assertStockInvariant();
        assertProblem(
                client.post("/api/orders/" + order.get("id").asString() + "/cancel", null), 409, type("order-state"));
    }

    @Test
    void readsShowAnOverdueOrderAsExpiredWithoutWritingAnything() {
        AdminClient client = customer("reader");
        JsonNode order = placeOrderFor(client, LAMP, 1);
        clock.advance(Duration.ofMinutes(16));

        JsonNode single = ok(client.get("/api/orders/" + order.get("id").asString()));
        JsonNode listed = ok(client.get("/api/orders")).get("items").get(0);

        assertThat(single.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(listed.get("status").asString()).isEqualTo("EXPIRED");
        assertThat(orderStatus(order.get("id").asString())).isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(lampId)).isEqualTo(1);
    }

    @Test
    void anOrderSurvivesAProductBeingDeactivatedAndRemoved() {
        AdminClient client = customer("history");
        JsonNode order = placeOrderFor(client, NOTEBOOK, 1);
        jdbc.sql("UPDATE product SET active = false WHERE slug = :slug")
                .param("slug", NOTEBOOK)
                .update();
        CatalogFixtures.setStock(jdbc, notebookId, 0, 0);

        JsonNode read = ok(client.get("/api/orders/" + order.get("id").asString()));

        assertThat(read.get("lines").get(0).get("productSlug").asString()).isEqualTo(NOTEBOOK);
        assertThat(read.get("status").asString()).isEqualTo("PENDING_PAYMENT");
    }
}
