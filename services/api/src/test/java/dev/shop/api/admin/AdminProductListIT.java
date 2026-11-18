package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AuthIT;
import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.Identity;
import dev.shop.api.support.TestBrowser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AdminProductListIT extends AuthIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JdbcClient jdbc;

    private TestBrowser admin;

    @BeforeEach
    void seed() {
        CatalogFixtures.clear(jdbc);
        CatalogFixtures.addCategory(jdbc, "office", "Office");
        CatalogFixtures.addProduct(jdbc, "walnut-desk", "Walnut Desk", "Solid", 49900, "office", true);
        CatalogFixtures.addProduct(jdbc, "brass-lamp", "Brass Lamp", "Bright", 1990, "office", true);
        CatalogFixtures.addProduct(jdbc, "retired-chair", "Retired Chair", "Gone", 5000, "office", false);
        admin = signedInAs(Identity.admin());
    }

    @Test
    void listsEveryProductIncludingInactiveOnesSortedByName() throws Exception {
        JsonNode page = JSON.readTree(admin.get("/api/admin/products").body());

        assertThat(names(page)).containsExactly("Brass Lamp", "Retired Chair", "Walnut Desk");
        assertThat(page.get("totalItems").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
        JsonNode retired = page.get("items").get(1);
        assertThat(retired.get("active").asBoolean()).isFalse();
        assertThat(retired.get("id").asLong()).isPositive();
        assertThat(retired.get("price").get("amountMinor").asLong()).isEqualTo(5000);
        assertThat(retired.get("category").get("slug").asString()).isEqualTo("office");
    }

    @Test
    void filtersByNameOrSlugIgnoringCase() throws Exception {
        assertThat(names(JSON.readTree(admin.get("/api/admin/products?q=LAMP").body())))
                .containsExactly("Brass Lamp");
        assertThat(names(JSON.readTree(
                        admin.get("/api/admin/products?q=retired-ch").body())))
                .containsExactly("Retired Chair");
        assertThat(names(JSON.readTree(admin.get("/api/admin/products?q=%20%20").body())))
                .hasSize(3);
    }

    @Test
    void pagesTheResult() throws Exception {
        JsonNode second =
                JSON.readTree(admin.get("/api/admin/products?page=1&size=2").body());

        assertThat(names(second)).containsExactly("Walnut Desk");
        assertThat(second.get("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void rejectsOutOfRangeParameters() {
        assertThat(admin.get("/api/admin/products?size=101").statusCode()).isEqualTo(400);
        assertThat(admin.get("/api/admin/products?page=10001").statusCode()).isEqualTo(400);
        assertThat(admin.get("/api/admin/products?q=a%00b").statusCode()).isEqualTo(400);
    }

    @Test
    void theResponseIsNotCached() {
        assertThat(admin.get("/api/admin/products")
                        .headers()
                        .firstValue("Cache-Control")
                        .orElseThrow())
                .contains("no-store");
    }

    private static List<String> names(JsonNode page) {
        return page.get("items")
                .valueStream()
                .map(item -> item.get("name").asString())
                .toList();
    }
}
