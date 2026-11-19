package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class StockIT extends AdminIT {

    private static final String VALIDATION = "tag:shop.dev,2026:validation";

    @Autowired
    private DataSource dataSource;

    private long id;
    private String stockUrl;

    @BeforeEach
    void findTheProduct() {
        id = CatalogFixtures.idOf(jdbc, "walnut-desk");
        stockUrl = "/api/admin/products/" + id + "/stock";
    }

    private static String stock(int onHand, long version) {
        return "{\"onHand\":" + onHand + ",\"version\":" + version + "}";
    }

    @Test
    void setsTheQuantityBumpsOnlyTheStockVersionAndAuditsOnHandWithReserved() {
        CatalogFixtures.setStock(jdbc, id, 0, 0);

        HttpResponse<String> response = admin.put(stockUrl, stock(7, 0));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode product = AdminClient.json(response);
        assertThat(product.get("stock").toString()).isEqualTo("{\"onHand\":7,\"reserved\":0,\"version\":1}");
        assertThat(product.get("version").asLong()).isZero();
        assertThat(auditRowsSinceTheTestStarted()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("action", "stock.set").containsEntry("entity_type", "stock");
            assertThat(row).containsEntry("entity_id", id);
            assertThat(AdminClient.parse(row.get("changes").toString()))
                    .isEqualTo(
                            AdminClient.parse("{\"onHand\":{\"from\":0,\"to\":7},\"reserved\":{\"from\":0,\"to\":0}}"));
        });
    }

    @Test
    void settingTheQuantityItAlreadyHasChangesAndAuditsNothing() {
        assertThat(admin.put(stockUrl, stock(7, 0)).statusCode()).isEqualTo(200);
        long rows = auditRowsSinceTheTestStarted().size();

        JsonNode again = AdminClient.json(admin.put(stockUrl, stock(7, 1)));

        assertThat(again.get("stock").get("version").asLong()).isEqualTo(1);
        assertThat(auditRowsSinceTheTestStarted()).hasSize((int) rows);
    }

    @Test
    void aStaleVersionIsAConflictAndWritesNothing() {
        admin.put(stockUrl, stock(7, 0));
        long rows = auditRowsSinceTheTestStarted().size();

        assertProblem(admin.put(stockUrl, stock(9, 0)), 409, "tag:shop.dev,2026:version-conflict");

        assertThat(AdminClient.json(admin.get("/api/admin/products/" + id))
                        .get("stock")
                        .get("onHand")
                        .asInt())
                .isEqualTo(7);
        assertThat(auditRowsSinceTheTestStarted()).hasSize((int) rows);
    }

    @Test
    void enforcesTheBoundsOfTheQuantityAndTheVersion() {
        assertProblem(admin.put(stockUrl, stock(-1, 0)), 400, VALIDATION, "onHand");
        assertProblem(admin.put(stockUrl, stock(1_000_001, 0)), 400, VALIDATION, "onHand");
        assertProblem(admin.put(stockUrl, stock(5, -1)), 400, VALIDATION, "version");
        assertProblem(admin.put(stockUrl, "{}"), 400, VALIDATION, "onHand", "version");
        assertThat(admin.put(stockUrl, stock(1_000_000, 0)).statusCode()).isEqualTo(200);
    }

    @Test
    void refusesAQuantityBelowTheReservedOneAndAllowsExactlyTheReservedOne() {
        CatalogFixtures.setStock(jdbc, id, 10, 4);

        assertProblem(admin.put(stockUrl, stock(3, 0)), 400, VALIDATION, "onHand");
        assertNoAuditRows();
        assertThat(admin.put(stockUrl, stock(4, 0)).statusCode()).isEqualTo(200);
    }

    @Test
    void aChangeToTheReservedQuantityAloneDoesNotMakeAnOpenFormStale() {
        JsonNode before = AdminClient.json(admin.get("/api/admin/products/" + id));
        CatalogFixtures.setStock(jdbc, id, 10, 3);

        assertThat(admin.put(
                                stockUrl,
                                stock(12, before.get("stock").get("version").asLong()))
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void anUnknownProductIsNotFound() {
        assertProblem(admin.put("/api/admin/products/987654/stock", stock(1, 0)), 404, "about:blank");
        assertProblem(admin.put("/api/admin/products/0/stock", stock(1, 0)), 400, VALIDATION, "id");
    }

    @Test
    void theListShowsTheStockOfEveryProduct() {
        CatalogFixtures.setStock(jdbc, id, 11, 1);

        JsonNode page = AdminClient.json(admin.get("/api/admin/products"));

        assertThat(page.get("items").get(0).get("stock").get("onHand").asInt()).isEqualTo(11);
        assertThat(page.get("items").get(0).get("stock").get("reserved").asInt())
                .isEqualTo(1);
    }

    @Test
    void twoRequestsWithTheSameVersionGiveOneSuccessOneConflictAndOneAuditRow() {
        CompletableFuture<HttpResponse<String>> first =
                CompletableFuture.supplyAsync(() -> admin.put(stockUrl, stock(5, 0)));
        CompletableFuture<HttpResponse<String>> second =
                CompletableFuture.supplyAsync(() -> admin.put(stockUrl, stock(6, 0)));

        List<Integer> statuses =
                List.of(first.join().statusCode(), second.join().statusCode());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(auditRowsSinceTheTestStarted()).hasSize(1);
    }

    @Test
    void aRowLockHeldByAnotherTransactionMakesTheRequestWaitAndThenSeeTheNewVersion() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            execute(other, "SELECT product_id FROM stock WHERE product_id = ? FOR UPDATE");
            CompletableFuture<HttpResponse<String>> request =
                    CompletableFuture.supplyAsync(() -> admin.put(stockUrl, stock(5, 0)));

            Thread.sleep(300);
            assertThat(request).isNotDone();
            execute(other, "UPDATE stock SET on_hand = 8, version = version + 1 WHERE product_id = ?");
            other.commit();

            assertProblem(request.get(10, TimeUnit.SECONDS), 409, "tag:shop.dev,2026:version-conflict");
        }
        assertNoAuditRows();
    }

    @Test
    void aLockThatIsNotReleasedInTimeIsA503WithRetryAfterNotAConflict() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            execute(other, "SELECT product_id FROM stock WHERE product_id = ? FOR UPDATE");

            HttpResponse<String> response = admin.put(stockUrl, stock(5, 0));

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After")).contains("3");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("application/problem+json");
            other.rollback();
        }
        assertNoAuditRows();
        assertThat(admin.put(stockUrl, stock(5, 0)).statusCode()).isEqualTo(200);
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.execute();
        }
    }
}
