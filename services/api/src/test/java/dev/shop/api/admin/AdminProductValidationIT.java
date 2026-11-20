package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.AdminClient;
import dev.shop.api.support.CatalogFixtures;
import java.net.http.HttpResponse;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

/** Every rule of the request records, at its boundary, through the real HTTP stack. */
class AdminProductValidationIT extends AdminIT {

    private static final String VALIDATION = "tag:shop.dev,2026:validation";
    private static final String LONE_SURROGATE_MARKER = "@@lone-surrogate@@";

    private static ObjectNode createBase() {
        return (ObjectNode) AdminClient.parse(NEW_PRODUCT.formatted("fresh-slug", "Fresh"));
    }

    private static ObjectNode updateBase() {
        return (ObjectNode) AdminClient.parse(update("Walnut Desk", "Solid", 49900, "office", true, 0));
    }

    private static Arguments change(String label, String field, Consumer<ObjectNode> mutation) {
        return Arguments.of(label, field, mutation);
    }

    private static Stream<Arguments> textRules(String field) {
        return Stream.of(
                change(field + " empty", field, node -> node.put(field, "")),
                change(field + " blank", field, node -> node.put(field, "  ")),
                change(field + " null", field, node -> node.putNull(field)),
                change(field + " missing", field, node -> node.remove(field)),
                change(field + " bell", field, node -> node.put(field, "a\u0007b")),
                change(field + " tab", field, node -> node.put(field, "a\tb")),
                change(field + " carriage return", field, node -> node.put(field, "a\rb")),
                change(field + " next line U+0085", field, node -> node.put(field, "a\u0085b")),
                change(field + " zero width space U+200B", field, node -> node.put(field, "a​b")),
                change(field + " right-to-left override U+202E", field, node -> node.put(field, "a‮b")),
                change(field + " private use U+E000", field, node -> node.put(field, "ab")),
                change(field + " lone surrogate", field, node -> node.put(field, LONE_SURROGATE_MARKER)));
    }

    static Stream<Arguments> invalidCreates() {
        return Stream.of(
                        textRules("name"),
                        textRules("description"),
                        Stream.of(
                                change("name line feed", "name", node -> node.put("name", "a\nb")),
                                change("name 121", "name", node -> node.put("name", "n".repeat(121))),
                                change(
                                        "description 2001",
                                        "description",
                                        node -> node.put("description", "d".repeat(2001))),
                                change("slug missing", "slug", node -> node.remove("slug")),
                                change("slug null", "slug", node -> node.putNull("slug")),
                                change("slug empty", "slug", node -> node.put("slug", "")),
                                change("slug upper case", "slug", node -> node.put("slug", "Bad-Slug")),
                                change("slug double hyphen", "slug", node -> node.put("slug", "a--b")),
                                change("slug 101", "slug", node -> node.put("slug", "s".repeat(101))),
                                change("price negative", "priceMinor", node -> node.put("priceMinor", -1)),
                                change("price 10000001", "priceMinor", node -> node.put("priceMinor", 10_000_001)),
                                change("price missing", "priceMinor", node -> node.remove("priceMinor")),
                                change("price a fraction", "priceMinor", node -> node.put("priceMinor", 12.5)),
                                change("price an exponent float", "priceMinor", node -> node.put("priceMinor", 1.0e3)),
                                change("price a string", "priceMinor", node -> node.put("priceMinor", "1299")),
                                change("category missing", "categorySlug", node -> node.remove("categorySlug")),
                                change(
                                        "category 101",
                                        "categorySlug",
                                        node -> node.put("categorySlug", "c".repeat(101))),
                                change("category unknown", "categorySlug", node -> node.put("categorySlug", "nowhere")),
                                change("active missing", "active", node -> node.remove("active")),
                                change("active a string", "active", node -> node.put("active", "true"))))
                .flatMap(stream -> stream);
    }

    @ParameterizedTest(name = "create with {0}")
    @MethodSource("invalidCreates")
    void rejectsAnInvalidCreateNamingTheField(String ignored, String field, Consumer<ObjectNode> mutation) {
        ObjectNode body = createBase();
        mutation.accept(body);

        HttpResponse<String> response = admin.post("/api/admin/products", rawJson(body));

        assertProblem(response, 400, VALIDATION, field);
        assertThat(jdbc.sql("SELECT count(*) FROM product").query(Long.class).single())
                .isEqualTo(1);
        assertNoAuditRows();
    }

    static Stream<Arguments> invalidUpdates() {
        return Stream.of(
                change("name bell", "name", node -> node.put("name", "a\u0007b")),
                change("description tab", "description", node -> node.put("description", "a\tb")),
                change("price a fraction", "priceMinor", node -> node.put("priceMinor", 12.5)),
                change("price negative", "priceMinor", node -> node.put("priceMinor", -1)),
                change("category missing", "categorySlug", node -> node.remove("categorySlug")),
                change("active missing", "active", node -> node.remove("active")),
                change("version missing", "version", node -> node.remove("version")),
                change("version negative", "version", node -> node.put("version", -1)),
                change("version a fraction", "version", node -> node.put("version", 1.5)),
                change("version a string", "version", node -> node.put("version", "0")));
    }

    @ParameterizedTest(name = "update with {0}")
    @MethodSource("invalidUpdates")
    void rejectsAnInvalidUpdateNamingTheField(String ignored, String field, Consumer<ObjectNode> mutation) {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");
        ObjectNode body = updateBase();
        mutation.accept(body);

        HttpResponse<String> response = admin.put("/api/admin/products/" + id, rawJson(body));

        assertProblem(response, 400, VALIDATION, field);
        assertNoAuditRows();
    }

    @Test
    void acceptsANameThatIsOnlyANonBreakingSpace() {
        ObjectNode body = createBase();
        body.put("name", " ");

        assertThat(admin.post("/api/admin/products", rawJson(body)).statusCode())
                .isEqualTo(201);
    }

    @Test
    void acceptsAMultiLineDescriptionAndTheBoundaryLengths() {
        ObjectNode body = createBase();
        body.put("name", "n".repeat(120)).put("description", "line one\nline two" + "d".repeat(1983));
        body.put("slug", "s".repeat(100)).put("priceMinor", 10_000_000);

        assertThat(admin.post("/api/admin/products", rawJson(body)).statusCode())
                .isEqualTo(201);
    }

    @Test
    void rejectsStockInputThatIsNotAnInteger() {
        long id = CatalogFixtures.idOf(jdbc, "walnut-desk");

        assertProblem(
                admin.put("/api/admin/products/" + id + "/stock", "{\"onHand\":1.0e0,\"version\":0}"),
                400,
                VALIDATION,
                "onHand");
        assertProblem(
                admin.put("/api/admin/products/" + id + "/stock", "{\"onHand\":1,\"version\":\"0\"}"),
                400,
                VALIDATION,
                "version");
    }

    @Test
    void rejectsUnreadableBodies() {
        assertThat(admin.post("/api/admin/products", "{").statusCode()).isEqualTo(400);
        HttpResponse<String> notAnObject = admin.post("/api/admin/products", "[]");
        assertThat(notAnObject.statusCode()).isEqualTo(400);
        assertThat(notAnObject.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertNoAuditRows();
    }

    @Test
    void listParametersAreValidatedWithTheParameterNameAsTheField() {
        assertProblem(admin.get("/api/admin/products?size=101"), 400, VALIDATION, "size");
        assertProblem(admin.get("/api/admin/products?page=-1"), 400, VALIDATION, "page");
        assertProblem(admin.get("/api/admin/products?q=a%00b"), 400, VALIDATION, "q");
        assertProblem(admin.get("/api/admin/audit?size=0"), 400, VALIDATION, "size");
    }

    private static String rawJson(ObjectNode body) {
        return body.toString().replace(LONE_SURROGATE_MARKER, "\\ud800");
    }
}
