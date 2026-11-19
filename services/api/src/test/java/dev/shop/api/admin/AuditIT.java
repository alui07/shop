package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AuditIT extends AdminIT {

    private static final String SENT_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void recordsTheActorSubjectAndNameAndOnlyTheFieldsThatChanged() {
        long id = createProduct("audited", "Audited").get("id").asLong();
        admin.put("/api/admin/products/" + id, update("Audited", "A fine thing", 1500, "office", true, 0));

        List<Map<String, Object>> rows = auditRowsSinceTheTestStarted();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).containsEntry("actor_subject", "admin-1").containsEntry("actor_name", "Alex Admin");
            assertThat(row).containsEntry("entity_id", id);
        });
        assertThat(AdminClient.parse(rows.get(0).get("changes").toString())).isEqualTo(AdminClient.parse("""
                        {"slug":{"from":null,"to":"audited"},"name":{"from":null,"to":"Audited"},
                         "description":{"from":null,"to":"A fine thing"},"priceMinor":{"from":null,"to":1299},
                         "categorySlug":{"from":null,"to":"office"},"active":{"from":null,"to":true}}
                        """));
        assertThat(AdminClient.parse(rows.get(1).get("changes").toString()))
                .isEqualTo(AdminClient.parse("{\"priceMinor\":{\"from\":1299,\"to\":1500}}"));
    }

    @Test
    void storesTheTraceIdOfTheRequestAndTheOneAClientSendsIsTheOneStored() {
        admin.post("/api/admin/products", NEW_PRODUCT.formatted("untraced", "Untraced"));
        admin.post(
                "/api/admin/products",
                NEW_PRODUCT.formatted("traced", "Traced"),
                "traceparent",
                "00-" + SENT_TRACE_ID + "-00f067aa0ba902b7-01");

        List<Map<String, Object>> rows = auditRowsSinceTheTestStarted();

        assertThat(rows.get(0).get("trace_id").toString()).matches("[0-9a-f]{32}");
        assertThat(rows.get(1)).containsEntry("trace_id", SENT_TRACE_ID);
    }

    @Test
    void servesTheNewestFirstWithValuesAsText() {
        long id = createProduct("first", "First").get("id").asLong();
        admin.put("/api/admin/products/" + id + "/stock", "{\"onHand\":4,\"version\":0}");

        JsonNode page = AdminClient.json(admin.get("/api/admin/audit?size=100"));

        JsonNode newest = page.get("items").get(0);
        assertThat(newest.get("action").asString()).isEqualTo("stock.set");
        assertThat(newest.get("entityType").asString()).isEqualTo("stock");
        assertThat(newest.get("entityId").asLong()).isEqualTo(id);
        assertThat(newest.get("actorSubject").asString()).isEqualTo("admin-1");
        assertThat(newest.get("actorName").asString()).isEqualTo("Alex Admin");
        assertThat(newest.get("occurredAt").asString()).isNotBlank();
        assertThat(newest.get("traceId").asString()).matches("[0-9a-f]{32}");
        assertThat(newest.get("changes").toString())
                .isEqualTo(
                        "[{\"field\":\"onHand\",\"from\":\"0\",\"to\":\"4\"},{\"field\":\"reserved\",\"from\":\"0\",\"to\":\"0\"}]");
        JsonNode created = page.get("items").get(1);
        assertThat(created.get("action").asString()).isEqualTo("product.created");
        assertThat(created.get("changes").get(0).toString())
                .isEqualTo("{\"field\":\"active\",\"from\":null,\"to\":\"true\"}");
        assertThat(created.get("id").asLong()).isLessThan(newest.get("id").asLong());
    }

    @Test
    void pagesTheLog() {
        for (int i = 0; i < 3; i++) {
            createProduct("paged-" + i, "Paged " + i);
        }

        JsonNode first = AdminClient.json(admin.get("/api/admin/audit?page=0&size=2"));
        JsonNode second = AdminClient.json(admin.get("/api/admin/audit?page=1&size=2"));

        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("totalItems").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(first.get("totalPages").asInt())
                .isEqualTo((int) ((first.get("totalItems").asLong() + 1) / 2));
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("items").get(0).get("id").asLong())
                .isLessThan(first.get("items").get(1).get("id").asLong());
        assertThat(admin.get("/api/admin/audit")
                        .headers()
                        .firstValue("Cache-Control")
                        .orElseThrow())
                .contains("no-store");
    }

    @Test
    void theTableRefusesUpdateDeleteAndTruncate() {
        createProduct("immutable", "Immutable");

        assertThatThrownBy(() -> jdbc.sql("UPDATE admin_audit_event SET actor_name = 'x'")
                        .update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM admin_audit_event").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE admin_audit_event").update())
                .hasMessageContaining("append-only");
        assertThat(auditRowsSinceTheTestStarted()).hasSize(1);
        assertThat(CatalogFixtures.auditHighWaterMark(jdbc)).isGreaterThan(auditMark);
    }
}
