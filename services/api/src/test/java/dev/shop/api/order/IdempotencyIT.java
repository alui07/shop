package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.ShopIT;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class IdempotencyIT extends ShopIT {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private IdempotencyStore store;

    @Test
    void repeatingARequestReplaysTheSameOrderAndCreatesNoSecondOne() {
        AdminClient client = customer("idem-replay");
        putLine(client, LAMP, 2);
        JsonNode cart = cart(client);
        UUID key = UUID.randomUUID();
        long version = cart.get("version").asLong();
        long total = cart.get("total").get("amountMinor").asLong();

        HttpResponse<String> first = checkout(client, key, version, total);
        HttpResponse<String> second = checkout(client, key, version, total);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotency-Replayed")).hasValue("true");
        assertThat(second.headers().firstValue("Location"))
                .isEqualTo(first.headers().firstValue("Location"));
        assertThat(AdminClient.json(second).get("id"))
                .isEqualTo(AdminClient.json(first).get("id"));
        assertThat(count("orders")).isEqualTo(1);
        assertThat(reserved(lampId)).isEqualTo(2);
        assertStockInvariant();
    }

    @Test
    void aReplayShowsTheCurrentStateOfTheOrder() {
        AdminClient client = customer("idem-current");
        putLine(client, LAMP, 1);
        JsonNode cart = cart(client);
        UUID key = UUID.randomUUID();
        JsonNode order = AdminClient.json(checkout(
                client,
                key,
                cart.get("version").asLong(),
                cart.get("total").get("amountMinor").asLong()));
        client.post("/api/orders/" + order.get("id").asString() + "/cancel", null);

        HttpResponse<String> replay = checkout(
                client,
                key,
                cart.get("version").asLong(),
                cart.get("total").get("amountMinor").asLong());

        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(AdminClient.json(replay).get("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void theSameKeyWithAnotherBodyIsUnprocessableAndChangesNothing() {
        AdminClient client = customer("idem-reuse");
        putLine(client, LAMP, 2);
        JsonNode cart = cart(client);
        UUID key = UUID.randomUUID();
        long version = cart.get("version").asLong();
        long total = cart.get("total").get("amountMinor").asLong();
        assertThat(checkout(client, key, version, total).statusCode()).isEqualTo(201);

        assertProblem(checkout(client, key, version, total + 1), 422, type("idempotency-key-reused"));
        assertProblem(checkout(client, key, version + 1, total), 422, type("idempotency-key-reused"));

        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void twoIdenticalRequestsAtOnceProduceOneOrder() {
        AdminClient client = customer("idem-parallel");
        putLine(client, LAMP, 1);
        JsonNode cart = cart(client);
        UUID key = UUID.randomUUID();
        long version = cart.get("version").asLong();
        long total = cart.get("total").get("amountMinor").asLong();

        CompletableFuture<HttpResponse<String>> first =
                CompletableFuture.supplyAsync(() -> checkout(client, key, version, total));
        CompletableFuture<HttpResponse<String>> second =
                CompletableFuture.supplyAsync(() -> checkout(client, key, version, total));
        List<HttpResponse<String>> responses = List.of(first.join(), second.join());

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(201, 201);
        assertThat(responses.stream()
                        .map(response -> AdminClient.json(response).get("id").asString())
                        .distinct())
                .hasSize(1);
        assertThat(responses.stream()
                        .filter(response -> response.headers()
                                .firstValue("Idempotency-Replayed")
                                .isPresent()))
                .hasSize(1);
        assertThat(count("orders")).isEqualTo(1);
        assertStockInvariant();
    }

    @Test
    void aRequestWaitingOnARunningOneAnswersInProgressWhenTheWaitTimesOut() throws Exception {
        AdminClient client = customer("idem-progress");
        putLine(client, LAMP, 1);
        JsonNode cart = cart(client);
        UUID key = UUID.randomUUID();
        try (Connection running = dataSource.getConnection()) {
            running.setAutoCommit(false);
            try (PreparedStatement claim = running.prepareStatement("""
                    INSERT INTO idempotency_key (customer_subject, key, request_hash, created_at, expires_at)
                    VALUES ('idem-progress', ?, 'h', now(), now() + interval '1 day')
                    """)) {
                claim.setObject(1, key);
                claim.execute();
            }

            HttpResponse<String> response = checkout(
                    client,
                    key,
                    cart.get("version").asLong(),
                    cart.get("total").get("amountMinor").asLong());

            assertProblem(response, 409, type("idempotency-in-progress"));
            assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
            running.rollback();
        }
        assertThat(count("orders")).isZero();
    }

    @Test
    void theSameKeyFromAnotherCustomerIsIndependent() {
        UUID key = UUID.randomUUID();
        AdminClient first = customer("idem-a");
        AdminClient second = customer("idem-b");
        putLine(first, LAMP, 1);
        putLine(second, LAMP, 1);

        HttpResponse<String> a = checkoutWith(first, key);
        HttpResponse<String> b = checkoutWith(second, key);

        assertThat(a.statusCode()).isEqualTo(201);
        assertThat(b.statusCode()).isEqualTo(201);
        assertThat(b.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(AdminClient.json(a).get("id"))
                .isNotEqualTo(AdminClient.json(b).get("id"));
    }

    @Test
    void anExpiredKeyCanBeUsedAgainEvenForAnotherRequest() {
        AdminClient client = customer("idem-expired");
        UUID key = UUID.randomUUID();
        putLine(client, LAMP, 1);
        JsonNode first = AdminClient.json(checkoutWith(client, key));
        jdbc.sql("UPDATE idempotency_key SET expires_at = expires_at - interval '2 days'")
                .update();

        putLine(client, NOTEBOOK, 2);
        HttpResponse<String> again = checkoutWith(client, key);

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(again.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(AdminClient.json(again).get("id")).isNotEqualTo(first.get("id"));
        assertThat(count("orders")).isEqualTo(2);
    }

    @Test
    void aFailedCheckoutLeavesNoKeyAndTheSameKeyWorksOnceTheCartIsRight() {
        AdminClient client = customer("idem-failed");
        UUID key = UUID.randomUUID();
        putLine(client, LAMP, 1);
        JsonNode cart = cart(client);

        assertProblem(checkout(client, key, cart.get("version").asLong() + 5, 0), 409, type("cart-changed"));
        assertThat(count("idempotency_key")).isZero();

        HttpResponse<String> retry = checkout(
                client,
                key,
                cart.get("version").asLong(),
                cart.get("total").get("amountMinor").asLong());
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(count("idempotency_key")).isEqualTo(1);
    }

    @Test
    void theMaintenanceJobDeletesOnlyExpiredKeysInBatches() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (int i = 0; i < 5; i++) {
            insertKey("old-" + i, now.minusHours(1));
        }
        insertKey("live", now.plusHours(1));
        MaintenanceJob job = new MaintenanceJob(store, new CheckoutProperties(null, 3, 10, null, 50, 2), clock);

        assertThat(job.runOnce()).isEqualTo(5);

        assertThat(count("idempotency_key")).isEqualTo(1);
        assertThat(job.runOnce()).isZero();
    }

    private HttpResponse<String> checkoutWith(AdminClient client, UUID key) {
        JsonNode cart = cart(client);
        return checkout(
                client,
                key,
                cart.get("version").asLong(),
                cart.get("total").get("amountMinor").asLong());
    }

    private void insertKey(String subject, OffsetDateTime expiresAt) {
        jdbc.sql("""
                        INSERT INTO idempotency_key (customer_subject, key, request_hash, created_at, expires_at)
                        VALUES (:subject, :key, 'h', :created, :expires)
                        """)
                .param("subject", subject)
                .param("key", UUID.randomUUID())
                .param("created", expiresAt.minusDays(1))
                .param("expires", expiresAt)
                .update();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }
}
