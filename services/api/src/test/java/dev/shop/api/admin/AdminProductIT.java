package dev.shop.api.admin;

import static dev.shop.api.support.HttpGet.get;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AdminProductIT extends AdminIT {

    @Autowired
    private DataSource dataSource;

    @Test
    void createsAProductWithEmptyStockAndOneAuditRow() {
        HttpResponse<String> response =
                admin.post("/api/admin/products", NEW_PRODUCT.formatted("oak-shelf", "Oak Shelf"));

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode created = AdminClient.json(response);
        assertThat(response.headers().firstValue("Location"))
                .contains("/api/admin/products/" + created.get("id").asLong());
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(created.get("version").asLong()).isZero();
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(created.get("price").get("currency").asString()).isEqualTo("EUR");
        assertThat(created.get("price").get("amountMinor").asLong()).isEqualTo(1299);
        assertThat(created.get("stock").toString()).isEqualTo("{\"onHand\":0,\"reserved\":0,\"version\":0}");
        assertThat(auditRowsSinceTheTestStarted())
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("action", "product.created"));
    }

    @Test
    void ignoresPropertiesTheClientMustNotChooseOnCreate() {
        String body = NEW_PRODUCT
                .formatted("sneaky", "Sneaky")
                .replace("{", "{\"id\":999,\"version\":7,\"reserved\":5,\"stock\":{\"onHand\":50},");

        JsonNode created = AdminClient.json(admin.post("/api/admin/products", body));

        assertThat(created.get("id").asLong()).isNotEqualTo(999);
        assertThat(created.get("version").asLong()).isZero();
        assertThat(created.get("stock").get("onHand").asInt()).isZero();
        assertThat(created.get("stock").get("reserved").asInt()).isZero();
    }

    @Test
    void ignoresTheSlugAndIdOnUpdate() {
        JsonNode created = createProduct("fixed-slug", "Fixed");
        long id = created.get("id").asLong();
        String body = update("Renamed", "Text", 100, "office", true, 0).replace("{", "{\"id\":999,\"slug\":\"other\",");

        JsonNode updated = AdminClient.json(admin.put("/api/admin/products/" + id, body));

        assertThat(updated.get("id").asLong()).isEqualTo(id);
        assertThat(updated.get("slug").asString()).isEqualTo("fixed-slug");
    }

    @Test
    void readsAProductWithItsStockAndRejectsAnUnknownOrInvalidId() {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");
        CatalogFixtures.setStock(jdbc, id, 9, 2);

        JsonNode found = AdminClient.json(admin.get("/api/admin/products/" + id));

        assertThat(found.get("name").asString()).isEqualTo("Walnut Desk");
        assertThat(found.get("stock").get("onHand").asInt()).isEqualTo(9);
        assertThat(found.get("stock").get("reserved").asInt()).isEqualTo(2);
        assertProblem(admin.get("/api/admin/products/987654"), 404, "about:blank");
        assertProblem(admin.get("/api/admin/products/0"), 400, "tag:shop.dev,2026:validation", "id");
    }

    static Stream<Arguments> singleFieldChanges() {
        return Stream.of(
                Arguments.of(
                        "name",
                        update("New Name", "Solid", 49900, "office", true, 0),
                        "{\"name\":{\"from\":\"Walnut Desk\",\"to\":\"New Name\"}}"),
                Arguments.of(
                        "description",
                        update("Walnut Desk", "Line one\\nline two", 49900, "office", true, 0),
                        "{\"description\":{\"from\":\"Solid\",\"to\":\"Line one\\nline two\"}}"),
                Arguments.of(
                        "price",
                        update("Walnut Desk", "Solid", 45000, "office", true, 0),
                        "{\"priceMinor\":{\"from\":49900,\"to\":45000}}"),
                Arguments.of(
                        "category",
                        update("Walnut Desk", "Solid", 49900, "garden", true, 0),
                        "{\"categorySlug\":{\"from\":\"office\",\"to\":\"garden\"}}"),
                Arguments.of(
                        "active",
                        update("Walnut Desk", "Solid", 49900, "office", false, 0),
                        "{\"active\":{\"from\":true,\"to\":false}}"));
    }

    @ParameterizedTest(name = "changing only the {0}")
    @MethodSource("singleFieldChanges")
    void updatesOneFieldBumpsTheVersionAndAuditsOnlyThatField(String ignored, String body, String expectedChanges) {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");

        HttpResponse<String> response = admin.put("/api/admin/products/" + id, body);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(AdminClient.json(response).get("version").asLong()).isEqualTo(1);
        assertThat(auditRowsSinceTheTestStarted()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("action", "product.updated").containsEntry("entity_type", "product");
            assertThat(row).containsEntry("entity_id", id).containsEntry("actor_subject", "admin-1");
            assertThat(row).containsEntry("actor_name", "Alex Admin");
            assertThat(AdminClient.parse(row.get("changes").toString())).isEqualTo(AdminClient.parse(expectedChanges));
        });
    }

    @Test
    void anUpdateThatChangesNothingKeepsTheVersionAndWritesNoAuditRow() {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");

        JsonNode unchanged = AdminClient.json(
                admin.put("/api/admin/products/" + id, update("Walnut Desk", "Solid", 49900, "office", true, 0)));

        assertThat(unchanged.get("version").asLong()).isZero();
        assertNoAuditRows();
    }

    @Test
    void aStaleVersionIsAConflictThatChangesAndAuditsNothing() {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");
        assertThat(admin.put("/api/admin/products/" + id, update("First", "Solid", 49900, "office", true, 0))
                        .statusCode())
                .isEqualTo(200);
        long auditRowsAfterFirstChange = auditRowsSinceTheTestStarted().size();

        HttpResponse<String> stale =
                admin.put("/api/admin/products/" + id, update("Second", "Solid", 49900, "office", true, 0));

        assertProblem(stale, 409, "tag:shop.dev,2026:version-conflict");
        assertThat(AdminClient.json(admin.get("/api/admin/products/" + id))
                        .get("name")
                        .asString())
                .isEqualTo("First");
        assertThat(auditRowsSinceTheTestStarted()).hasSize((int) auditRowsAfterFirstChange);
    }

    @Test
    void aDuplicateSlugIsAConflictOnTheSlugField() {
        HttpResponse<String> duplicate =
                admin.post("/api/admin/products", NEW_PRODUCT.formatted("walnut-desk", "Another"));

        assertProblem(duplicate, 409, "tag:shop.dev,2026:duplicate-slug", "slug");
        assertNoAuditRows();
    }

    @Test
    void anUnknownProductIsNotFoundAndAnUnknownCategoryIsABadRequest() {
        assertProblem(
                admin.put("/api/admin/products/987654", update("N", "D", 1, "office", true, 0)), 404, "about:blank");
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");

        assertProblem(
                admin.put("/api/admin/products/" + id, update("N", "D", 1, "nowhere", true, 0)),
                400,
                "tag:shop.dev,2026:validation",
                "categorySlug");
        assertNoAuditRows();
    }

    @Test
    void anInactiveProductDisappearsFromEveryPublicViewAndReturnsWhenReactivated() {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");
        String[] publicUrls = {
            "/api/products", "/api/products?q=walnut", "/api/products?category=office", "/api/products/walnut-desk"
        };

        assertThat(publicUrls)
                .allSatisfy(url -> assertThat(get(port, url).statusCode()).isEqualTo(200));
        admin.put("/api/admin/products/" + id, update("Walnut Desk", "Solid", 49900, "office", false, 0));
        assertThat(get(port, "/api/products").body()).doesNotContain("walnut-desk");
        assertThat(get(port, "/api/products?q=walnut").body()).doesNotContain("walnut-desk");
        assertThat(get(port, "/api/products?category=office").body()).doesNotContain("walnut-desk");
        assertThat(get(port, "/api/products/walnut-desk").statusCode()).isEqualTo(404);
        assertThat(admin.get("/api/admin/products?q=walnut").body()).contains("walnut-desk");

        admin.put("/api/admin/products/" + id, update("Walnut Desk", "Solid", 49900, "office", true, 1));

        assertThat(publicUrls)
                .allSatisfy(url -> assertThat(get(port, url).statusCode()).isEqualTo(200));
        assertThat(get(port, "/api/products?q=walnut").body()).contains("walnut-desk");
    }

    @Test
    void aRowLockHeldByAnotherTransactionMakesAnUpdateWaitAndThenSeeTheNewVersion() throws Exception {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT id FROM product WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, id);
                lock.executeQuery();
            }
            CompletableFuture<HttpResponse<String>> request = CompletableFuture.supplyAsync(
                    () -> admin.put("/api/admin/products/" + id, update("Late", "Solid", 49900, "office", true, 0)));

            Thread.sleep(300);
            assertThat(request).isNotDone();
            try (PreparedStatement bump =
                    other.prepareStatement("UPDATE product SET version = version + 1 WHERE id = ?")) {
                bump.setLong(1, id);
                bump.executeUpdate();
            }
            other.commit();

            assertProblem(request.get(10, TimeUnit.SECONDS), 409, "tag:shop.dev,2026:version-conflict");
        }
        assertNoAuditRows();
    }
}
