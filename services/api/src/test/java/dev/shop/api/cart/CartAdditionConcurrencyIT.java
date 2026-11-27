package dev.shop.api.cart;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.ShopIT;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.RepeatedTest;
import tools.jackson.databind.JsonNode;

/** Every writer of one customer's cart queues on the cart row, so the cap holds and no addition is lost. */
class CartAdditionConcurrencyIT extends ShopIT {

    private static final int REPEATS = 5;

    @RepeatedTest(REPEATS)
    void twelveSingleAdditionsEndAtExactlyTenWithTwoRefused() {
        AdminClient client = customer("race-ones-" + UUID.randomUUID());

        List<HttpResponse<String>> responses = runTogether(12, () -> addToLine(client, LAMP, 1, UUID.randomUUID()));

        assertThat(responses)
                .filteredOn(response -> response.statusCode() == 200)
                .hasSize(10);
        assertThat(responses.stream().filter(response -> response.statusCode() == 409))
                .hasSize(2)
                .allSatisfy(response -> assertProblem(response, 409, type("line-quantity-limit")));
        JsonNode cart = cart(client);
        assertThat(cart.get("lines").get(0).get("quantity").asInt()).isEqualTo(10);
        assertThat(cart.get("version").asLong()).isEqualTo(10);
    }

    @RepeatedTest(REPEATS)
    void eightAdditionsOfThreeLetExactlyThreeIn() {
        AdminClient client = customer("race-threes-" + UUID.randomUUID());

        List<HttpResponse<String>> responses = runTogether(8, () -> addToLine(client, LAMP, 3, UUID.randomUUID()));

        assertThat(responses)
                .filteredOn(response -> response.statusCode() == 200)
                .hasSize(3);
        assertThat(responses)
                .filteredOn(response -> response.statusCode() == 409)
                .hasSize(5);
        assertThat(cart(client).get("lines").get(0).get("quantity").asInt()).isEqualTo(9);
    }

    @RepeatedTest(REPEATS)
    void twoRequestsWithTheSameKeyAddOnce() {
        AdminClient client = customer("race-same-" + UUID.randomUUID());
        UUID key = UUID.randomUUID();

        List<HttpResponse<String>> responses = runTogether(2, () -> addToLine(client, LAMP, 4, key));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(200, 200);
        assertThat(responses.stream()
                        .filter(response -> response.headers()
                                .firstValue("Idempotency-Replayed")
                                .isPresent()))
                .hasSize(1);
        JsonNode cart = cart(client);
        assertThat(cart.get("lines").get(0).get("quantity").asInt()).isEqualTo(4);
        assertThat(cart.get("version").asLong()).isEqualTo(1);
    }

    private static <T> List<T> runTogether(int threads, Supplier<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<T>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> CompletableFuture.supplyAsync(
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
