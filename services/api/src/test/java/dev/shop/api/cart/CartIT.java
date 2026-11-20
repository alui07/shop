package dev.shop.api.cart;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.ShopIT;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class CartIT extends ShopIT {

    private static final String VALIDATION = "tag:shop.dev,2026:validation";

    @Test
    void aCustomerWithoutACartSeesAnEmptyOneAtVersionZero() {
        AdminClient client = customer("cart-empty");

        HttpResponse<String> response = client.get("/api/cart");
        JsonNode cart = ok(response);

        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(cart.get("version").asLong()).isZero();
        assertThat(cart.get("lines")).isEmpty();
        assertThat(cart.get("itemCount").asInt()).isZero();
        assertThat(cart.get("total").toString()).isEqualTo("{\"amountMinor\":0,\"currency\":\"EUR\"}");
    }

    @Test
    void addingLinesBumpsTheVersionAndPricesThemFromTheCatalog() {
        AdminClient client = customer("cart-add");

        JsonNode first = putLine(client, LAMP, 2);
        JsonNode second = putLine(client, NOTEBOOK, 3);

        assertThat(first.get("version").asLong()).isEqualTo(1);
        assertThat(second.get("version").asLong()).isEqualTo(2);
        assertThat(second.get("itemCount").asInt()).isEqualTo(5);
        assertThat(second.get("total").get("amountMinor").asLong()).isEqualTo(2 * LAMP_PRICE + 3 * NOTEBOOK_PRICE);
        JsonNode lamp = second.get("lines").get(0);
        assertThat(lamp.get("productSlug").asString()).isEqualTo(LAMP);
        assertThat(lamp.get("name").asString()).isEqualTo("Desk Lamp");
        assertThat(lamp.get("unitPrice").get("amountMinor").asLong()).isEqualTo(LAMP_PRICE);
        assertThat(lamp.get("quantity").asInt()).isEqualTo(2);
        assertThat(lamp.get("lineTotal").get("amountMinor").asLong()).isEqualTo(2 * LAMP_PRICE);
        assertThat(lamp.get("available").asBoolean()).isTrue();
        assertThat(cart(client).toString()).isEqualTo(second.toString());
    }

    @Test
    void changingAQuantityBumpsTheVersionAndSettingTheSameQuantityDoesNot() {
        AdminClient client = customer("cart-change");
        putLine(client, LAMP, 2);

        JsonNode changed = putLine(client, LAMP, 5);
        JsonNode unchanged = putLine(client, LAMP, 5);

        assertThat(changed.get("version").asLong()).isEqualTo(2);
        assertThat(changed.get("itemCount").asInt()).isEqualTo(5);
        assertThat(unchanged.get("version").asLong()).isEqualTo(2);
    }

    @Test
    void eachCustomerHasTheirOwnCart() {
        putLine(customer("cart-a"), LAMP, 1);

        assertThat(cart(customer("cart-b")).get("lines")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"quantity\":0}", "{\"quantity\":11}", "{\"quantity\":-1}", "{}", "{\"quantity\":null}"})
    void aQuantityOutsideOneToTenIsRefused(String body) {
        HttpResponse<String> response = customer("cart-bad").put("/api/cart/lines/" + LAMP, body);

        assertProblem(response, 400, VALIDATION, "quantity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"quantity\":1.5}", "{\"quantity\":\"3\"}", "{\"quantity\":true}"})
    void aQuantityOfTheWrongTypeIsRefused(String body) {
        HttpResponse<String> response = customer("cart-type").put("/api/cart/lines/" + LAMP, body);

        assertProblem(response, 400, VALIDATION, "quantity");
    }

    @Test
    void anUnknownOrInactiveProductIsNotFoundAndNoCartIsCreated() {
        AdminClient client = customer("cart-missing");

        assertProblem(client.put("/api/cart/lines/nothing-here", "{\"quantity\":1}"), 404, "about:blank");
        assertProblem(client.put("/api/cart/lines/" + RETIRED_PEN, "{\"quantity\":1}"), 404, "about:blank");

        assertThat(jdbc.sql("SELECT count(*) FROM cart").query(Integer.class).single())
                .isZero();
    }

    @Test
    void aSlugWithControlCharactersOrOverOneHundredCharactersIsRefused() {
        AdminClient client = customer("cart-slug");

        assertThat(client.put("/api/cart/lines/a%00b", "{\"quantity\":1}").statusCode())
                .isEqualTo(400);
        assertThat(client.put("/api/cart/lines/" + "a".repeat(101), "{\"quantity\":1}")
                        .statusCode())
                .isEqualTo(400);
        assertThat(client.sendJson("DELETE", "/api/cart/lines/a%00b", null).statusCode())
                .isEqualTo(400);
    }

    @Test
    void theTwentyFirstLineIsRefusedButExistingLinesCanStillChange() {
        AdminClient client = customer("cart-full");
        addProducts(20);
        for (int i = 1; i <= 20; i++) {
            putLine(client, "item-" + i, 1);
        }

        assertProblem(client.put("/api/cart/lines/" + LAMP, "{\"quantity\":1}"), 409, type("cart-full"));
        JsonNode changed = putLine(client, "item-7", 4);

        assertThat(changed.get("lines")).hasSize(20);
        assertThat(changed.get("version").asLong()).isEqualTo(21);
    }

    @Test
    void twoConcurrentAdditionsAtNineteenLinesLetExactlyOneIn() {
        AdminClient client = customer("cart-race");
        addProducts(19);
        for (int i = 1; i <= 19; i++) {
            putLine(client, "item-" + i, 1);
        }

        CompletableFuture<HttpResponse<String>> lamp =
                CompletableFuture.supplyAsync(() -> client.put("/api/cart/lines/" + LAMP, "{\"quantity\":1}"));
        CompletableFuture<HttpResponse<String>> notebook =
                CompletableFuture.supplyAsync(() -> client.put("/api/cart/lines/" + NOTEBOOK, "{\"quantity\":1}"));

        assertThat(List.of(lamp.join().statusCode(), notebook.join().statusCode()))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(cart(client).get("lines")).hasSize(20);
    }

    @Test
    void aLineIsUnavailableWhenStockIsShortOrTheProductIsInactive() {
        AdminClient client = customer("cart-available");
        putLine(client, LAMP, 4);
        putLine(client, NOTEBOOK, 2);

        CatalogFixtures.setStock(jdbc, lampId, 10, 7);
        CatalogFixtures.setStock(jdbc, notebookId, 2, 0);
        JsonNode lampShort = cart(client);
        assertThat(availability(lampShort)).containsExactly(false, true);

        CatalogFixtures.setStock(jdbc, lampId, 10, 6);
        jdbc.sql("UPDATE product SET active = false WHERE slug = :slug")
                .param("slug", NOTEBOOK)
                .update();
        assertThat(availability(cart(client))).containsExactly(true, false);
    }

    @Test
    void removingALineBumpsTheVersionAndRemovingItAgainDoesNot() {
        AdminClient client = customer("cart-remove");
        putLine(client, LAMP, 1);
        putLine(client, NOTEBOOK, 1);

        JsonNode removed = ok(client.sendJson("DELETE", "/api/cart/lines/" + LAMP, null));
        JsonNode again = ok(client.sendJson("DELETE", "/api/cart/lines/" + LAMP, null));

        assertThat(removed.get("version").asLong()).isEqualTo(3);
        assertThat(removed.get("lines")).hasSize(1);
        assertThat(again.get("version").asLong()).isEqualTo(3);
    }

    @Test
    void removingFromANonexistentCartOrAnUnknownProductChangesNothing() {
        AdminClient client = customer("cart-remove-none");

        JsonNode noCart = ok(client.sendJson("DELETE", "/api/cart/lines/" + LAMP, null));
        assertThat(noCart.get("version").asLong()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM cart").query(Integer.class).single())
                .isZero();

        putLine(client, LAMP, 1);
        JsonNode unknown = ok(client.sendJson("DELETE", "/api/cart/lines/nothing-here", null));
        assertThat(unknown.get("version").asLong()).isEqualTo(1);
        assertThat(unknown.get("lines")).hasSize(1);
    }

    @Test
    void aDeactivatedProductsLineCanStillBeRemoved() {
        AdminClient client = customer("cart-remove-inactive");
        putLine(client, NOTEBOOK, 1);
        jdbc.sql("UPDATE product SET active = false WHERE slug = :slug")
                .param("slug", NOTEBOOK)
                .update();

        JsonNode removed = ok(client.sendJson("DELETE", "/api/cart/lines/" + NOTEBOOK, null));

        assertThat(removed.get("lines")).isEmpty();
        assertThat(removed.get("version").asLong()).isEqualTo(2);
    }

    private void addProducts(int count) {
        for (int i = 1; i <= count; i++) {
            CatalogFixtures.addProduct(jdbc, "item-" + i, "Item " + i, "Filler", 100, "office", true);
        }
    }

    private static List<Boolean> availability(JsonNode cart) {
        return cart.get("lines")
                .valueStream()
                .map(line -> line.get("available").asBoolean())
                .toList();
    }
}
