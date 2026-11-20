package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.Identity;
import dev.shop.api.support.ShopIT;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class CheckoutIT extends ShopIT {

    private static final String VALIDATION = "tag:shop.dev,2026:validation";

    @Autowired
    private DataSource dataSource;

    @Test
    void aCheckoutSnapshotsPricesReservesStockAndEmptiesTheCart() {
        AdminClient client = customer("buyer");
        putLine(client, LAMP, 2);
        putLine(client, NOTEBOOK, 1);
        JsonNode cartBefore = cart(client);

        HttpResponse<String> response = checkout(client, UUID.randomUUID(), 2, 2 * LAMP_PRICE + NOTEBOOK_PRICE);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode order = AdminClient.json(response);
        assertThat(response.headers().firstValue("Location"))
                .hasValue("/api/orders/" + order.get("id").asString());
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(order.get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(Duration.between(
                        Instant.parse(order.get("createdAt").asString()),
                        Instant.parse(order.get("expiresAt").asString())))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(order.get("total").get("amountMinor").asLong()).isEqualTo(2 * LAMP_PRICE + NOTEBOOK_PRICE);
        assertThat(order.get("lines")).hasSize(2);
        JsonNode lamp = order.get("lines").get(0);
        assertThat(lamp.get("productSlug").asString()).isEqualTo(LAMP);
        assertThat(lamp.get("name").asString()).isEqualTo("Desk Lamp");
        assertThat(lamp.get("unitPrice").get("amountMinor").asLong()).isEqualTo(LAMP_PRICE);
        assertThat(lamp.get("quantity").asInt()).isEqualTo(2);
        assertThat(lamp.get("lineTotal").get("amountMinor").asLong()).isEqualTo(2 * LAMP_PRICE);

        assertThat(reserved(lampId)).isEqualTo(2);
        assertThat(reserved(notebookId)).isEqualTo(1);
        assertStockInvariant();
        JsonNode cartAfter = cart(client);
        assertThat(cartAfter.get("lines")).isEmpty();
        assertThat(cartAfter.get("version").asLong())
                .isGreaterThan(cartBefore.get("version").asLong());
        assertThat(AdminClient.json(client.get(
                                response.headers().firstValue("Location").orElseThrow()))
                        .toString())
                .isEqualTo(order.toString());
    }

    @Test
    void anOrderKeepsThePricesOfTheMomentOfCheckout() {
        AdminClient client = customer("buyer-snapshot");
        JsonNode order = placeOrderFor(client, LAMP, 1);

        jdbc.sql("UPDATE product SET price_minor = 9999, name = 'Renamed' WHERE slug = :slug")
                .param("slug", LAMP)
                .update();

        JsonNode later =
                AdminClient.json(client.get("/api/orders/" + order.get("id").asString()));
        assertThat(later.get("lines").get(0).get("unitPrice").get("amountMinor").asLong())
                .isEqualTo(LAMP_PRICE);
        assertThat(later.get("lines").get(0).get("name").asString()).isEqualTo("Desk Lamp");
        assertThat(later.get("total").get("amountMinor").asLong()).isEqualTo(LAMP_PRICE);
    }

    @Test
    void aVerifiedEmailIsStoredOnTheOrderAndAnUnverifiedOneIsNot() {
        AdminClient verified = customer("buyer-verified");
        AdminClient unverified = new AdminClient(
                signedInAs(Identity.customer().withSubject("buyer-unverified").withEmailVerified(false)));

        JsonNode first = placeOrderFor(verified, LAMP, 1);
        JsonNode second = placeOrderFor(unverified, LAMP, 1);

        assertThat(emailOf(first)).isEqualTo("customer@example.test");
        assertThat(emailOf(second)).isNull();
    }

    @Test
    void aPriceChangeBetweenTheCartAndTheCheckoutIsRefusedAndNothingIsHeld() {
        AdminClient client = customer("buyer-price");
        JsonNode seen = putLine(client, LAMP, 2);
        jdbc.sql("UPDATE product SET price_minor = price_minor + 1 WHERE slug = :slug")
                .param("slug", LAMP)
                .update();

        HttpResponse<String> response = checkout(
                client,
                UUID.randomUUID(),
                seen.get("version").asLong(),
                seen.get("total").get("amountMinor").asLong());

        assertProblem(response, 409, type("price-changed"));
        assertThat(reserved(lampId)).isZero();
        assertThat(count("orders")).isZero();
        assertThat(cart(client).get("lines")).hasSize(1);
    }

    @Test
    void aProductDeactivatedAfterItWasAddedIsOutOfStock() {
        AdminClient client = customer("buyer-inactive");
        putLine(client, LAMP, 1);
        putLine(client, NOTEBOOK, 1);
        jdbc.sql("UPDATE product SET active = false WHERE slug IN ('notebook', 'desk-lamp')")
                .update();

        assertProblem(checkoutCurrentCart(client, UUID.randomUUID()), 409, type("out-of-stock"), LAMP, NOTEBOOK);
        assertThat(count("orders")).isZero();
    }

    @Test
    void aStaleCartVersionAnEmptyCartAndNoCartAreAllCartChanged() {
        AdminClient client = customer("buyer-stale");
        assertProblem(checkout(client, UUID.randomUUID(), 0, 0), 409, type("cart-changed"));

        JsonNode seen = putLine(client, LAMP, 1);
        putLine(client, LAMP, 2);
        assertProblem(
                checkout(
                        client,
                        UUID.randomUUID(),
                        seen.get("version").asLong(),
                        seen.get("total").get("amountMinor").asLong()),
                409,
                type("cart-changed"));

        JsonNode emptied = ok(client.sendJson("DELETE", "/api/cart/lines/" + LAMP, null));
        assertProblem(
                checkout(client, UUID.randomUUID(), emptied.get("version").asLong(), 0), 409, type("cart-changed"));
        assertThat(count("orders")).isZero();
    }

    @Test
    void outOfStockNamesEveryShortLineAndLeavesStockUnchanged() {
        AdminClient client = customer("buyer-short");
        putLine(client, LAMP, 2);
        putLine(client, NOTEBOOK, 2);

        CatalogFixtures.setStock(jdbc, lampId, 1, 0);
        CatalogFixtures.setStock(jdbc, notebookId, 1, 0);
        assertProblem(checkoutCurrentCart(client, UUID.randomUUID()), 409, type("out-of-stock"), LAMP, NOTEBOOK);

        CatalogFixtures.setStock(jdbc, lampId, 1, 0);
        CatalogFixtures.setStock(jdbc, notebookId, 5, 0);
        assertProblem(checkoutCurrentCart(client, UUID.randomUUID()), 409, type("out-of-stock"), LAMP);

        CatalogFixtures.setStock(jdbc, lampId, 5, 0);
        CatalogFixtures.setStock(jdbc, notebookId, 1, 0);
        assertProblem(checkoutCurrentCart(client, UUID.randomUUID()), 409, type("out-of-stock"), NOTEBOOK);

        assertThat(reserved(lampId)).isZero();
        assertThat(reserved(notebookId)).isZero();
        assertThat(count("reservation")).isZero();
        assertThat(count("orders")).isZero();
        assertThat(cart(client).get("lines")).hasSize(2);
    }

    @Test
    void aFourthOrderAwaitingPaymentIsRefusedAndCancelledOrExpiredOnesDoNotCount() {
        AdminClient client = customer("buyer-cap");
        JsonNode first = placeOrderFor(client, NOTEBOOK, 1);
        placeOrderFor(client, NOTEBOOK, 1);
        placeOrderFor(client, NOTEBOOK, 1);

        putLine(client, NOTEBOOK, 1);
        assertProblem(checkoutCurrentCart(client, UUID.randomUUID()), 409, type("too-many-pending-orders"));

        assertThat(client.post("/api/orders/" + first.get("id").asString() + "/cancel", null)
                        .statusCode())
                .isEqualTo(200);
        assertThat(checkoutCurrentCart(client, UUID.randomUUID()).statusCode()).isEqualTo(201);

        putLine(client, NOTEBOOK, 1);
        assertProblem(checkoutCurrentCart(client, UUID.randomUUID()), 409, type("too-many-pending-orders"));
        clock.advance(Duration.ofMinutes(16));
        assertThat(checkoutCurrentCart(client, UUID.randomUUID()).statusCode()).isEqualTo(201);
    }

    @Test
    void theCapIsPerCustomer() {
        for (int i = 0; i < 3; i++) {
            placeOrderFor(customer("buyer-cap-a"), NOTEBOOK, 1);
        }
        putLine(customer("buyer-cap-b"), NOTEBOOK, 1);

        assertThat(placeOrder(customer("buyer-cap-b")).get("status").asString()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void unpaidOrdersMayHoldTenUnitsOfOneProductInTotal() {
        AdminClient client = customer("buyer-limit");
        placeOrderFor(client, LAMP, 6);

        putLine(client, LAMP, 5);
        assertProblem(checkoutCurrentCart(client, UUID.randomUUID()), 409, type("reservation-limit"), LAMP);
        assertThat(reserved(lampId)).isEqualTo(6);

        putLine(client, LAMP, 4);
        assertThat(checkoutCurrentCart(client, UUID.randomUUID()).statusCode()).isEqualTo(201);
        assertThat(reserved(lampId)).isEqualTo(10);
        assertStockInvariant();
    }

    @Test
    void tenUnitsInOneOrderAreAllowedAndTheLimitIsPerProductAndCustomer() {
        AdminClient client = customer("buyer-ten");
        assertThat(placeOrderFor(client, LAMP, 10).get("status").asString()).isEqualTo("PENDING_PAYMENT");

        assertThat(placeOrderFor(client, NOTEBOOK, 10).get("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(reserved(lampId)).isEqualTo(10);
        assertThat(reserved(notebookId)).isEqualTo(10);
    }

    @Test
    void theHoldOfAnExpiredOrderNoLongerCountsAgainstTheLimit() {
        AdminClient client = customer("buyer-limit-expired");
        placeOrderFor(client, LAMP, 10);
        clock.advance(Duration.ofMinutes(16));
        CatalogFixtures.setStock(jdbc, lampId, 20, 0);

        putLine(client, LAMP, 10);

        assertThat(checkoutCurrentCart(client, UUID.randomUUID()).statusCode()).isEqualTo(201);
    }

    @Test
    void aRequestWithoutTheCsrfTokenIsForbidden() {
        AdminClient client = customer("buyer-csrf");
        putLine(client, LAMP, 1);
        JsonNode cart = cart(client);

        HttpResponse<String> response =
                browserWithoutCsrf(client, cart.get("version").asLong());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void theIdempotencyKeyIsRequiredAndMustBeAUuid() {
        AdminClient client = customer("buyer-key");
        String body = "{\"cartVersion\":0,\"expectedTotalMinor\":0}";

        assertThat(client.post("/api/checkout", body).statusCode()).isEqualTo(400);
        assertThat(client.post("/api/checkout", body, "Idempotency-Key", "not-a-uuid")
                        .statusCode())
                .isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"cartVersion\":-1,\"expectedTotalMinor\":0}",
                "{\"cartVersion\":0,\"expectedTotalMinor\":-1}",
                "{\"cartVersion\":0,\"expectedTotalMinor\":1000000000001}",
                "{\"cartVersion\":null,\"expectedTotalMinor\":0}"
            })
    void aBodyOutsideTheBoundsIsRefused(String body) {
        HttpResponse<String> response = customer("buyer-body")
                .post(
                        "/api/checkout",
                        body,
                        "Idempotency-Key",
                        UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(AdminClient.json(response).get("type").asString()).isEqualTo(VALIDATION);
    }

    @Test
    void aBodyWithTheWrongTypeIsRefused() {
        HttpResponse<String> response = customer("buyer-type")
                .post(
                        "/api/checkout",
                        "{\"cartVersion\":1.5,\"expectedTotalMinor\":0}",
                        "Idempotency-Key",
                        UUID.randomUUID().toString());

        assertProblem(response, 400, VALIDATION, "cartVersion");
    }

    @Test
    void aStockLockHeldTooLongIsA503NotAConflictAndNothingIsKept() throws Exception {
        AdminClient client = customer("buyer-lock");
        putLine(client, LAMP, 1);
        JsonNode cart = cart(client);
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock =
                    other.prepareStatement("SELECT 1 FROM stock WHERE product_id = ? FOR UPDATE")) {
                lock.setLong(1, lampId);
                lock.execute();
            }

            HttpResponse<String> response = checkout(
                    client,
                    UUID.randomUUID(),
                    cart.get("version").asLong(),
                    cart.get("total").get("amountMinor").asLong());

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After")).isPresent();
            other.rollback();
        }
        assertThat(count("orders")).isZero();
        assertThat(count("idempotency_key")).isZero();
        assertThat(checkoutCurrentCart(client, UUID.randomUUID()).statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> browserWithoutCsrf(AdminClient client, long version) {
        return client.postWithoutCsrf(
                "/api/checkout",
                "{\"cartVersion\":" + version + ",\"expectedTotalMinor\":1500}",
                "Idempotency-Key",
                UUID.randomUUID().toString());
    }

    private String emailOf(JsonNode order) {
        return jdbc.sql("SELECT email FROM orders WHERE id = :id")
                .param("id", UUID.fromString(order.get("id").asString()))
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
}
