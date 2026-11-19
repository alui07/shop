package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/** The only class with a spy, so only one extra application context (and its containers) starts. */
class AuditRollbackIT extends AdminIT {

    @MockitoSpyBean
    private AuditRepository auditRepository;

    private void auditInsertFails() {
        doThrow(new IllegalStateException("audit store down"))
                .when(auditRepository)
                .insert(any(), any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void aFailedAuditInsertRollsBackAProductCreate() {
        auditInsertFails();

        HttpResponse<String> response = admin.post("/api/admin/products", NEW_PRODUCT.formatted("ghost", "Ghost"));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(jdbc.sql("SELECT count(*) FROM product WHERE slug = 'ghost'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM stock").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void aFailedAuditInsertRollsBackAProductUpdate() {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");
        auditInsertFails();

        HttpResponse<String> response =
                admin.put("/api/admin/products/" + id, update("Changed", "Solid", 49900, "office", true, 0));

        assertThat(response.statusCode()).isEqualTo(500);
        JsonNode product = AdminClient.json(admin.get("/api/admin/products/" + id));
        assertThat(product.get("name").asString()).isEqualTo("Walnut Desk");
        assertThat(product.get("version").asLong()).isZero();
    }

    @Test
    void aFailedAuditInsertRollsBackAStockChange() {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");
        auditInsertFails();

        HttpResponse<String> response =
                admin.put("/api/admin/products/" + id + "/stock", "{\"onHand\":5,\"version\":0}");

        assertThat(response.statusCode()).isEqualTo(500);
        JsonNode stock =
                AdminClient.json(admin.get("/api/admin/products/" + id)).get("stock");
        assertThat(stock.get("onHand").asInt()).isZero();
        assertThat(stock.get("version").asLong()).isZero();
        assertNoAuditRows();
    }
}
