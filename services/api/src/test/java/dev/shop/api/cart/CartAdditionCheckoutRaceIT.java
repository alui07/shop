package dev.shop.api.cart;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.ShopIT;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.RepeatedTest;
import tools.jackson.databind.JsonNode;

/**
 * Additions and a checkout of one customer's cart all lock the idempotency row, then the cart row, so they queue
 * without deadlock. Checkout carries the version it saw: it either wins the cart lock before every addition, or it is
 * refused as cart-changed.
 */
class CartAdditionCheckoutRaceIT extends ShopIT {

    private static final int REPEATS = 5;
    private static final int ADDITIONS = 5;

    @RepeatedTest(REPEATS)
    void checkoutEitherTakesTheCartAsSeenOrIsRefusedAndNoAdditionIsLost() {
        AdminClient client = customer("race-checkout-" + UUID.randomUUID());
        putLine(client, LAMP, 1);
        long seenVersion = cart(client).get("version").asLong();

        List<Supplier<HttpResponse<String>>> tasks = new ArrayList<>();
        tasks.add(() -> checkout(client, UUID.randomUUID(), seenVersion, LAMP_PRICE));
        for (int i = 0; i < ADDITIONS; i++) {
            tasks.add(() -> addToLine(client, LAMP, 1, UUID.randomUUID()));
        }

        List<HttpResponse<String>> responses = runTogether(tasks);

        HttpResponse<String> checkoutResponse = responses.get(0);
        List<HttpResponse<String>> additions = responses.subList(1, responses.size());
        assertThat(additions).extracting(HttpResponse::statusCode).containsOnly(200);
        JsonNode cart = cart(client);
        int inCart = cart.get("itemCount").asInt();
        if (checkoutResponse.statusCode() == 201) {
            JsonNode order = AdminClient.json(checkoutResponse);
            assertThat(order.get("lines")).hasSize(1);
            assertThat(order.get("lines").get(0).get("quantity").asInt()).isEqualTo(1);
            assertThat(order.get("total").get("amountMinor").asLong()).isEqualTo(LAMP_PRICE);
            assertThat(inCart)
                    .as("additions committed after checkout cleared the cart")
                    .isEqualTo(ADDITIONS);
            assertThat(cart.get("version").asLong()).isEqualTo(seenVersion + 1 + ADDITIONS);
            assertThat(reserved(lampId)).isEqualTo(1);
        } else {
            assertProblem(checkoutResponse, 409, type("cart-changed"));
            assertThat(inCart).isEqualTo(1 + ADDITIONS);
            assertThat(cart.get("version").asLong()).isEqualTo(seenVersion + ADDITIONS);
            assertThat(reserved(lampId)).isZero();
        }
        assertStockInvariant();
    }

    private static <T> List<T> runTogether(List<Supplier<T>> tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<T>> futures = tasks.stream()
                    .map(task -> CompletableFuture.supplyAsync(
                            () -> {
                                await(go);
                                return task.get();
                            },
                            pool))
                    .toList();
            go.countDown();
            return futures.stream().map(CompletableFuture::join).toList();
        } finally {
            pool.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
