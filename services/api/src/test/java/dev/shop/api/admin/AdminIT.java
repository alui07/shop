package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.AuthIT;
import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.Identity;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** A small catalog, a signed-in admin and helpers to read what the admin API wrote. */
abstract class AdminIT extends AuthIT {

    static final String NEW_PRODUCT = """
            {"slug":"%s","name":"%s","description":"A fine thing","priceMinor":1299,"categorySlug":"office","active":true}
            """;
    static final String PRODUCT_UPDATE = """
            {"name":"%s","description":"%s","priceMinor":%d,"categorySlug":"%s","active":%s,"version":%d}
            """;

    @Autowired
    protected JdbcClient jdbc;

    protected AdminClient admin;
    protected long auditMark;

    @BeforeEach
    void loadCatalogAndSignIn() {
        CatalogFixtures.clear(jdbc);
        CatalogFixtures.addCategory(jdbc, "office", "Office");
        CatalogFixtures.addCategory(jdbc, "garden", "Garden");
        CatalogFixtures.addProduct(jdbc, "walnut-desk", "Walnut Desk", "Solid", 49900, "office", true);
        auditMark = CatalogFixtures.auditHighWaterMark(jdbc);
        admin = new AdminClient(signedInAs(Identity.admin()));
    }

    protected JsonNode createProduct(String slug, String name) {
        HttpResponse<String> response = admin.post("/api/admin/products", NEW_PRODUCT.formatted(slug, name));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return AdminClient.json(response);
    }

    protected static String update(
            String name, String description, long priceMinor, String category, boolean active, long version) {
        return PRODUCT_UPDATE.formatted(name, description, priceMinor, category, active, version);
    }

    protected List<Map<String, Object>> auditRowsSinceTheTestStarted() {
        return jdbc.sql("""
                        SELECT id, action, entity_type, entity_id, actor_subject, actor_name, trace_id,
                               changes::text AS changes
                        FROM admin_audit_event WHERE id > :mark ORDER BY id
                        """).param("mark", auditMark).query().listOfRows();
    }

    protected void assertNoAuditRows() {
        assertThat(auditRowsSinceTheTestStarted()).isEmpty();
    }

    protected static void assertProblem(HttpResponse<String> response, int status, String type, String... errorFields) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        JsonNode problem = AdminClient.json(response);
        assertThat(problem.has("type") ? problem.get("type").asString() : "about:blank")
                .isEqualTo(type);
        JsonNode errors = problem.get("errors");
        assertThat(
                        errors == null
                                ? Stream.<String>empty()
                                : errors.valueStream()
                                        .map(error -> error.get("field").asString())
                                        .distinct())
                .containsExactlyInAnyOrder(errorFields);
    }
}
