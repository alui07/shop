package dev.shop.api.cart;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.ShopIT;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class CartAdditionIT extends ShopIT {

    private static final String VALIDATION = "tag:shop.dev,2026:validation";

    @Autowired
    private DataSource dataSource;

    @Test
    void anAdditionCreatesTheLineAndTheCartThenAddsToItBumpingTheVersionOnce() {
        AdminClient client = customer("add-sum");

        HttpResponse<String> first = addToLine(client, LAMP, 2, UUID.randomUUID());
        JsonNode second = ok(addToLine(client, LAMP, 3, UUID.randomUUID()));

        JsonNode created = ok(first);
        assertThat(first.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(first.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(created.get("version").asLong()).isEqualTo(1);
        assertThat(created.get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(second.get("version").asLong()).isEqualTo(2);
        assertThat(second.get("lines")).hasSize(1);
        assertThat(second.get("lines").get(0).get("quantity").asInt()).isEqualTo(5);
        assertThat(second.get("itemCount").asInt()).isEqualTo(5);
        assertThat(cart(client).get("version").asLong()).isEqualTo(2);
    }

    @Test
    void aLineCanReachExactlyTenButNotMore() {
        AdminClient client = customer("add-cap");
        ok(addToLine(client, LAMP, 9, UUID.randomUUID()));

        assertQuantityLimit(addToLine(client, LAMP, 2, UUID.randomUUID()), 9);
        JsonNode full = ok(addToLine(client, LAMP, 1, UUID.randomUUID()));
        assertQuantityLimit(addToLine(client, LAMP, 1, UUID.randomUUID()), 10);

        assertThat(full.get("lines").get(0).get("quantity").asInt()).isEqualTo(10);
        assertThat(cart(client).get("version").asLong()).isEqualTo(2);
        assertThat(keyCount("add-cap")).isEqualTo(2);
    }

    @Test
    void aRefusedAdditionLeavesNoKeyAndTheSameKeyWorksOnceTheLineHasRoom() {
        AdminClient client = customer("add-retry");
        UUID key = UUID.randomUUID();
        ok(addToLine(client, LAMP, 9, UUID.randomUUID()));
        assertQuantityLimit(addToLine(client, LAMP, 2, key), 9);
        putLine(client, LAMP, 1);

        HttpResponse<String> retry = addToLine(client, LAMP, 2, key);

        assertThat(ok(retry).get("lines").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(retry.headers().firstValue("Idempotency-Replayed")).isEmpty();
    }

    @Test
    void theTwentyFirstLineIsRefusedButAnExistingLineCanStillBeAddedTo() {
        AdminClient client = customer("add-full");
        for (int i = 1; i <= 20; i++) {
            CatalogFixtures.addProduct(jdbc, "item-" + i, "Item " + i, "Filler", 100, "office", true);
            putLine(client, "item-" + i, 1);
        }

        assertProblem(addToLine(client, LAMP, 1, UUID.randomUUID()), 409, type("cart-full"));
        JsonNode added = ok(addToLine(client, "item-7", 4, UUID.randomUUID()));

        assertThat(added.get("lines")).hasSize(20);
        assertThat(added.get("version").asLong()).isEqualTo(21);
        assertThat(keyCount("add-full")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"quantity\":0}", "{\"quantity\":11}", "{\"quantity\":null}", "{}"})
    void aQuantityOutsideOneToTenIsRefused(String body) {
        assertProblem(post(customer("add-bad"), LAMP, body, UUID.randomUUID().toString()), 400, VALIDATION, "quantity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"quantity\":\"x\"}", "{\"quantity\":1.5}", "{\"quantity\":true}"})
    void aQuantityOfTheWrongTypeIsRefused(String body) {
        assertProblem(
                post(customer("add-type"), LAMP, body, UUID.randomUUID().toString()), 400, VALIDATION, "quantity");
    }

    @Test
    void aMissingBodyIsRefused() {
        AdminClient client = customer("add-nobody");

        HttpResponse<String> response = client.post(
                "/api/cart/lines/" + LAMP + "/additions",
                null,
                "Idempotency-Key",
                UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void aMissingOrEmptyKeyIsBadRequestAndANonUuidKeyIsAValidationProblemThatDoesNotEchoIt() {
        AdminClient client = customer("add-key");
        String path = "/api/cart/lines/" + LAMP + "/additions";

        HttpResponse<String> missing = client.post(path, "{\"quantity\":1}");
        HttpResponse<String> empty = client.post(path, "{\"quantity\":1}", "Idempotency-Key", "");
        HttpResponse<String> malformed = post(client, LAMP, "{\"quantity\":1}", "zq9-not-a-uuid");

        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(empty.statusCode()).isEqualTo(400);
        assertProblem(malformed, 400, VALIDATION, "Idempotency-Key");
        assertThat(malformed.body()).doesNotContain("zq9-not-a-uuid");
        assertThat(cart(client).get("version").asLong()).isZero();
    }

    @Test
    void aSlugWithControlCharactersOrOverOneHundredCharactersIsRefused() {
        AdminClient client = customer("add-slug");

        assertThat(addToLine(client, "a%00b", 1, UUID.randomUUID()).statusCode())
                .isEqualTo(400);
        assertThat(addToLine(client, "a".repeat(101), 1, UUID.randomUUID()).statusCode())
                .isEqualTo(400);
    }

    @Test
    void anUnknownOrInactiveProductIsNotFoundWithNoCartAndNoKeyKept() {
        AdminClient client = customer("add-missing");

        assertProblem(addToLine(client, "nothing-here", 1, UUID.randomUUID()), 404, "about:blank");
        assertProblem(addToLine(client, RETIRED_PEN, 1, UUID.randomUUID()), 404, "about:blank");

        assertThat(jdbc.sql("SELECT count(*) FROM cart").query(Integer.class).single())
                .isZero();
        assertThat(keyCount("add-missing")).isZero();
    }

    @Test
    void thereIsNoStockCheckSoAnAdditionBeyondFreeStockShowsAsUnavailable() {
        AdminClient client = customer("add-stock");
        CatalogFixtures.setStock(jdbc, lampId, 1, 0);

        JsonNode cart = ok(addToLine(client, LAMP, 5, UUID.randomUUID()));

        assertThat(cart.get("lines").get(0).get("available").asBoolean()).isFalse();
    }

    @Test
    void repeatingARequestAddsOnceAndReplaysTheCurrentCart() {
        AdminClient client = customer("add-replay");
        UUID key = UUID.randomUUID();

        HttpResponse<String> first = addToLine(client, LAMP, 2, key);
        HttpResponse<String> second = addToLine(client, LAMP, 2, key);
        putLine(client, LAMP, 6);
        HttpResponse<String> afterPut = addToLine(client, LAMP, 2, key);

        assertThat(first.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(second.headers().firstValue("Idempotency-Replayed")).hasValue("true");
        assertThat(ok(second).get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(ok(second).get("version").asLong()).isEqualTo(1);
        assertThat(afterPut.headers().firstValue("Idempotency-Replayed")).hasValue("true");
        assertThat(ok(afterPut).get("lines").get(0).get("quantity").asInt()).isEqualTo(6);
        assertThat(ok(afterPut).get("version").asLong()).isEqualTo(2);
    }

    @Test
    void theSameKeyWithAnotherQuantityOrSlugIsUnprocessableAndChangesNothing() {
        AdminClient client = customer("add-reuse");
        UUID key = UUID.randomUUID();
        ok(addToLine(client, LAMP, 2, key));

        assertProblem(addToLine(client, LAMP, 3, key), 422, type("idempotency-key-reused"));
        assertProblem(addToLine(client, NOTEBOOK, 2, key), 422, type("idempotency-key-reused"));

        JsonNode cart = cart(client);
        assertThat(cart.get("version").asLong()).isEqualTo(1);
        assertThat(cart.get("itemCount").asInt()).isEqualTo(2);
    }

    @Test
    void aKeyCannotCrossBetweenCheckoutAndAnAddition() {
        AdminClient client = customer("add-cross");
        UUID checkoutKey = UUID.randomUUID();
        UUID additionKey = UUID.randomUUID();
        putLine(client, LAMP, 1);
        assertThat(checkoutCurrentCart(client, checkoutKey).statusCode()).isEqualTo(201);
        ok(addToLine(client, LAMP, 1, additionKey));

        assertProblem(addToLine(client, LAMP, 1, checkoutKey), 422, type("idempotency-key-reused"));
        assertProblem(checkoutCurrentCart(client, additionKey), 422, type("idempotency-key-reused"));
    }

    @Test
    void theSameKeyFromAnotherCustomerIsIndependent() {
        UUID key = UUID.randomUUID();
        AdminClient first = customer("add-a");
        AdminClient second = customer("add-b");

        HttpResponse<String> a = addToLine(first, LAMP, 1, key);
        HttpResponse<String> b = addToLine(second, LAMP, 1, key);

        assertThat(ok(a).get("itemCount").asInt()).isEqualTo(1);
        assertThat(ok(b).get("itemCount").asInt()).isEqualTo(1);
        assertThat(b.headers().firstValue("Idempotency-Replayed")).isEmpty();
    }

    @Test
    void anExpiredKeyIsAcceptedAsNew() {
        AdminClient client = customer("add-expired");
        UUID key = UUID.randomUUID();
        ok(addToLine(client, LAMP, 2, key));
        jdbc.sql("UPDATE idempotency_key SET expires_at = expires_at - interval '2 days' WHERE customer_subject = :s")
                .param("s", "add-expired")
                .update();

        HttpResponse<String> again = addToLine(client, LAMP, 3, key);

        assertThat(again.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(ok(again).get("lines").get(0).get("quantity").asInt()).isEqualTo(5);
    }

    @Test
    void aRequestWaitingOnARunningOneAnswersInProgressWhenTheWaitTimesOut() throws Exception {
        AdminClient client = customer("add-progress");
        UUID key = UUID.randomUUID();
        try (Connection running = dataSource.getConnection()) {
            running.setAutoCommit(false);
            try (PreparedStatement claim = running.prepareStatement("""
                    INSERT INTO idempotency_key (customer_subject, key, request_hash, created_at, expires_at)
                    VALUES ('add-progress', ?, 'h', now(), now() + interval '1 day')
                    """)) {
                claim.setObject(1, key);
                claim.execute();
            }

            HttpResponse<String> response = addToLine(client, LAMP, 1, key);

            assertProblem(response, 409, type("idempotency-in-progress"));
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
            running.rollback();
        }
        assertThat(cart(client).get("version").asLong()).isZero();
    }

    private HttpResponse<String> post(AdminClient client, String slug, String body, String key) {
        return client.post("/api/cart/lines/" + slug + "/additions", body, "Idempotency-Key", key);
    }

    private int keyCount(String subject) {
        return jdbc.sql("SELECT count(*) FROM idempotency_key WHERE customer_subject = :s")
                .param("s", subject)
                .query(Integer.class)
                .single();
    }

    private static void assertQuantityLimit(HttpResponse<String> response, int quantityInCart) {
        assertProblem(response, 409, type("line-quantity-limit"));
        JsonNode problem = AdminClient.json(response);
        assertThat(problem.get("quantityInCart").asInt()).isEqualTo(quantityInCart);
        assertThat(problem.get("maxQuantity").asInt()).isEqualTo(10);
    }
}
