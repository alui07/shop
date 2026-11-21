package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.customer.CurrentUser;
import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.Identity;
import dev.shop.api.support.Principals;
import dev.shop.api.support.ShopIT;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponseException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Real threads and a real database, calling the checkout service the way the endpoint does. Hikari's pool bounds how
 * many run at once (10); that is still a genuine race on the stock rows.
 */
class StockRaceIT extends ShopIT {

    private static final String OUT_OF_STOCK = type("out-of-stock");
    private static final String REPETITIONS_NAME = "{displayName} {currentRepetition}/{totalRepetitions}";

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private ReservationExpiryJob expiryJob;

    @Autowired
    private OrderExpiry expiry;

    @Autowired
    private PostgreSQLContainer postgres;

    @RepeatedTest(value = 5, name = REPETITIONS_NAME)
    void fiftyCustomersWantingOneUnitEachOfTenGetExactlyTen() throws Exception {
        List<String> customers = seedCarts(50, lampId, 1);

        Outcomes outcomes = race(checkoutAsEach(customers));

        assertThat(outcomes.succeeded).isEqualTo(10);
        assertThat(outcomes.outOfStock).isEqualTo(40);
        assertThat(reserved(lampId)).isEqualTo(10);
        assertThat(orderCount()).isEqualTo(10);
        assertStockInvariant();
    }

    @RepeatedTest(value = 5, name = REPETITIONS_NAME)
    void twentyCustomersWantingThreeUnitsEachOfTenGetExactlyThree() throws Exception {
        List<String> customers = seedCarts(20, lampId, 3);

        Outcomes outcomes = race(checkoutAsEach(customers));

        assertThat(outcomes.succeeded).isEqualTo(3);
        assertThat(outcomes.outOfStock).isEqualTo(17);
        assertThat(reserved(lampId)).isEqualTo(9);
        assertStockInvariant();
    }

    @RepeatedTest(value = 5, name = REPETITIONS_NAME)
    void anAdminLoweringTheStockNeverLeavesItBelowWhatIsReserved() throws Exception {
        AdminClient admin = new AdminClient(signedInAs(Identity.admin()));
        List<String> customers = seedCarts(20, lampId, 1);
        List<Integer> adminStatuses = new CopyOnWriteArrayList<>();

        Outcomes outcomes = race(checkoutAsEach(customers), () -> {
            for (int onHand = 9; onHand >= 0; onHand--) {
                JsonNode product = AdminClient.json(admin.get("/api/admin/products/" + lampId));
                HttpResponse<String> response = admin.put(
                        "/api/admin/products/" + lampId + "/stock",
                        "{\"onHand\":" + onHand + ",\"version\":"
                                + product.get("stock").get("version").asLong() + "}");
                adminStatuses.add(response.statusCode());
            }
        });

        assertThat(adminStatuses).isNotEmpty().allMatch(status -> status == 200 || status == 400 || status == 409);
        assertThat(outcomes.succeeded).isEqualTo(reserved(lampId));
        assertStockInvariant();
        assertThat(jdbc.sql("SELECT on_hand >= reserved FROM stock WHERE product_id = :id")
                        .param("id", lampId)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @RepeatedTest(value = 5, name = REPETITIONS_NAME)
    void checkoutsAndTheExpiryJobRunningTogetherStayConsistent() throws Exception {
        CatalogFixtures.setStock(jdbc, lampId, 40, 0);
        for (int i = 0; i < 30; i++) {
            insertPendingOrder("old-order-" + i, clock.instant().minusSeconds(60), new long[] {lampId, 1});
        }
        List<String> customers = seedCarts(20, lampId, 1);

        Outcomes outcomes = race(checkoutAsEach(customers), expiryJob::runOnce);

        assertThat(outcomes.succeeded).isBetween(10, 20);
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE status = 'EXPIRED'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(30);
        assertThat(reserved(lampId)).isEqualTo(outcomes.succeeded);
        assertStockInvariant();
    }

    @RepeatedTest(value = 20, name = REPETITIONS_NAME)
    void expiryBatchesHoldingProductsInCrossedOrderRacingCheckoutsNeverDeadlock() throws Exception {
        jdbc.sql("UPDATE stock SET on_hand = 400").update();
        for (int i = 0; i < 40; i++) {
            long[] lamp = {lampId, 1};
            long[] notebook = {notebookId, 1};
            insertPendingOrder(
                    "crossed-" + i,
                    clock.instant().minusSeconds(60),
                    i % 2 == 0 ? new long[][] {lamp, notebook} : new long[][] {notebook, lamp});
        }
        ReservationExpiryJob first = new ReservationExpiryJob(expiry, smallBatches(), clock);
        ReservationExpiryJob second = new ReservationExpiryJob(expiry, smallBatches(), clock);
        List<String> customers = seedCartsWithBothProducts(10);

        Outcomes outcomes = race(checkoutAsEach(customers), first::runOnce, second::runOnce);
        first.runOnce(); // rows the other job held locked are skipped and wait for the next run

        assertThat(outcomes.succeeded + outcomes.outOfStock).isEqualTo(10);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM orders WHERE status = 'PENDING_PAYMENT' AND customer_subject LIKE 'crossed-%'")
                        .query(Integer.class)
                        .single())
                .isZero();
        assertStockInvariant();
        assertThat(postgres.getLogs()).doesNotContain("deadlock detected").doesNotContain("40P01");
    }

    private static CheckoutProperties smallBatches() {
        return new CheckoutProperties(
                Duration.ofMinutes(15), 3, 10, Duration.ofHours(24), 5, 1000, Duration.ofMinutes(30));
    }

    private List<String> seedCarts(int customers, long productId, int quantity) {
        List<String> subjects = new ArrayList<>();
        for (int i = 0; i < customers; i++) {
            String subject = "racer-" + i;
            seedCart(subject, new long[] {productId, quantity});
            subjects.add(subject);
        }
        return subjects;
    }

    private List<String> seedCartsWithBothProducts(int customers) {
        List<String> subjects = new ArrayList<>();
        for (int i = 0; i < customers; i++) {
            String subject = "racer-" + i;
            seedCart(subject, new long[] {lampId, 1}, new long[] {notebookId, 1});
            subjects.add(subject);
        }
        return subjects;
    }

    private void seedCart(String subject, long[]... lines) {
        long cartId = jdbc.sql("""
                        INSERT INTO cart (customer_subject, version, updated_at) VALUES (:subject, 1, now())
                        RETURNING id
                        """).param("subject", subject).query(Long.class).single();
        for (long[] line : lines) {
            jdbc.sql(
                            "INSERT INTO cart_line (cart_id, product_id, quantity, added_at) VALUES (:cart, :product, :quantity, now())")
                    .param("cart", cartId)
                    .param("product", line[0])
                    .param("quantity", (int) line[1])
                    .update();
        }
    }

    private List<Callable<Boolean>> checkoutAsEach(List<String> customers) {
        IntFunction<Callable<Boolean>> checkout = index -> () -> {
            String subject = customers.get(index);
            SecurityContextHolder.getContext().setAuthentication(Principals.customer(subject));
            try {
                long total = jdbc.sql("""
                                SELECT sum(p.price_minor * l.quantity) FROM cart c
                                JOIN cart_line l ON l.cart_id = c.id JOIN product p ON p.id = l.product_id
                                WHERE c.customer_subject = :subject
                                """)
                        .param("subject", subject)
                        .query(Long.class)
                        .single();
                checkoutService.checkout(
                        CurrentUser.of(SecurityContextHolder.getContext().getAuthentication()),
                        UUID.randomUUID(),
                        new CheckoutInput(1L, total));
                return true;
            } catch (ErrorResponseException refusal) {
                assertThat(refusal.getBody().getType()).hasToString(OUT_OF_STOCK);
                return false;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
        return IntStream.range(0, customers.size()).mapToObj(checkout).toList();
    }

    private record Outcomes(int succeeded, int outOfStock) {}

    /** Releases every task at once and fails on any exception other than the expected out-of-stock refusal. */
    private static Outcomes race(List<Callable<Boolean>> checkouts, Runnable... background) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(checkouts.size() + background.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> backgroundWork = Arrays.stream(background)
                    .map(task -> pool.submit(() -> {
                        start.await();
                        task.run();
                        return null;
                    }))
                    .collect(Collectors.toList());
            List<Future<Boolean>> futures = checkouts.stream()
                    .map(task -> pool.submit(() -> {
                        start.await();
                        return task.call();
                    }))
                    .toList();
            start.countDown();
            for (Future<?> work : backgroundWork) {
                work.get();
            }
            int succeeded = 0;
            int refused = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    succeeded++;
                } else {
                    refused++;
                }
            }
            return new Outcomes(succeeded, refused);
        } finally {
            pool.shutdownNow();
        }
    }

    private int orderCount() {
        return jdbc.sql("SELECT count(*) FROM orders").query(Integer.class).single();
    }
}
