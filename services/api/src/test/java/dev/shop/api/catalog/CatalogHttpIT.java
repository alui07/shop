package dev.shop.api.catalog;

import static dev.shop.api.support.HttpGet.get;
import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class CatalogHttpIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void loadCatalog() {
        CatalogFixtures.loadStandardCatalog(jdbc);
    }

    @Test
    void listsCategoriesByName() throws Exception {
        HttpResponse<String> response = get(port, "/api/categories");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(JSON.readTree(response.body()).findValuesAsString("slug"))
                .containsExactly("garden", "kitchen", "office");
    }

    @Test
    void listsTheFirstPageWithDefaultsAndRevalidationHeaders() throws Exception {
        HttpResponse<String> response = get(port, "/api/products");
        JsonNode page = JSON.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-cache");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(page.get("items")).hasSize(12);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(12);
        assertThat(page.get("totalItems").asLong()).isEqualTo(CatalogFixtures.ACTIVE_PRODUCTS);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("price").get("currency").asString()).isEqualTo("EUR");
        assertThat(first.get("category").get("slug").asString()).isNotBlank();
    }

    @Test
    void searchesFiltersSortsAndPages() throws Exception {
        JsonNode search = JSON.readTree(get(port, "/api/products?q=lamp").body());
        JsonNode inCategory =
                JSON.readTree(get(port, "/api/products?category=kitchen").body());
        JsonNode byPrice =
                JSON.readTree(get(port, "/api/products?q=lamp&sort=price_desc").body());
        JsonNode secondPage =
                JSON.readTree(get(port, "/api/products?page=2&size=12").body());

        assertThat(productSlugs(search)).containsExactly("brass-lamp", "table-lamp", "reading-light");
        assertThat(inCategory.get("totalItems").asLong()).isEqualTo(2);
        assertThat(productSlugs(byPrice)).containsExactly("brass-lamp", "table-lamp", "reading-light");
        assertThat(secondPage.get("items")).hasSize(6);
    }

    private static List<String> productSlugs(JsonNode page) {
        return page.get("items")
                .valueStream()
                .map(item -> item.get("slug").asString())
                .toList();
    }

    @Test
    void aPagePastTheEndIsAnEmptyPageWithCorrectTotals() throws Exception {
        HttpResponse<String> response = get(port, "/api/products?page=9");
        JsonNode page = JSON.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("totalItems").asLong()).isEqualTo(CatalogFixtures.ACTIVE_PRODUCTS);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "size=0",
                "size=49",
                "page=-1",
                "page=10001",
                "page=abc",
                "size=1.5",
                "sort=cheapest",
                "q=%00",
                "q=a%00b",
                "category=%00",
                "category=%0A",
                "q=%FF",
                "q=%ED%A0%80",
                "category=%FF",
                "sort=PRICE_ASC"
            })
    void rejectsInvalidParametersWithProblemDetails(String query) throws Exception {
        HttpResponse<String> response = get(port, "/api/products?" + query);
        JsonNode problem = JSON.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("title").asString()).isEqualTo("Bad Request");
        assertThat(problem.get("detail").asString()).isNotBlank();
        assertThat(response.body()).doesNotContain("Exception", "at dev.shop");
    }

    @ParameterizedTest
    @ValueSource(strings = {"oak%0Adesk", "%7F"})
    void rejectsControlCharactersInTheProductSlug(String slug) {
        HttpResponse<String> response = get(port, "/api/products/" + slug);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"brass%20lamp", "caf%C3%A9"})
    void acceptsOrdinarySearchTextWithSpacesAndAccents(String query) {
        assertThat(get(port, "/api/products?q=" + query).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsCategoryAndSlugOverOneHundredCharacters() {
        assertThat(get(port, "/api/products?category=" + "a".repeat(101)).statusCode())
                .isEqualTo(400);
        assertThat(get(port, "/api/products/" + "a".repeat(101)).statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsSearchTextOverOneHundredCharacters() {
        assertThat(get(port, "/api/products?q=" + "a".repeat(101)).statusCode()).isEqualTo(400);
        assertThat(get(port, "/api/products?q=" + "a".repeat(100)).statusCode()).isEqualTo(200);
    }

    @Test
    void servesProductDetail() throws Exception {
        HttpResponse<String> response = get(port, "/api/products/brass-lamp");
        JsonNode detail = JSON.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-cache");
        assertThat(detail.get("name").asString()).isEqualTo("Brass Lamp");
        assertThat(detail.get("description").asString()).isEqualTo("A tidy desk accessory");
        assertThat(detail.get("price").get("amountMinor").asLong()).isEqualTo(1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/products/no-such-product", "/api/products/hidden-lamp"})
    void unknownAndInactiveProductsAreProblemDetailsNotFound(String path) throws Exception {
        HttpResponse<String> response = get(port, path);
        JsonNode problem = JSON.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(problem.get("status").asInt()).isEqualTo(404);
        assertThat(problem.get("title").asString()).isEqualTo("Not Found");
    }

    @Test
    void errorsStayProblemDetailsWhenTheClientOnlyAcceptsJson() {
        HttpResponse<String> response =
                get(port, "/api/products/no-such-product", Map.of("Accept", "application/json"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    @Test
    void unknownPathsAreDeniedByDefaultWithAProblem() {
        HttpResponse<String> response = get(port, "/api/nothing");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
    }
}
