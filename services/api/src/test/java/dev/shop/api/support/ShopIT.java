package dev.shop.api.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** A small catalog with stock, signed-in customers and helpers for the cart and checkout endpoints. */
public abstract class ShopIT extends AuthIT {

    public static final String LAMP = "desk-lamp";
    public static final String NOTEBOOK = "notebook";
    public static final String RETIRED_PEN = "retired-pen";
    public static final long LAMP_PRICE = 1500;
    public static final long NOTEBOOK_PRICE = 400;

    @Autowired
    protected JdbcClient jdbc;

    protected long lampId;
    protected long notebookId;
    protected long retiredPenId;

    @BeforeEach
    void loadCatalogWithStock() {
        CatalogFixtures.clear(jdbc);
        CatalogFixtures.addCategory(jdbc, "office", "Office");
        CatalogFixtures.addProduct(jdbc, LAMP, "Desk Lamp", "Bright", LAMP_PRICE, "office", true);
        CatalogFixtures.addProduct(jdbc, NOTEBOOK, "Notebook", "Lined", NOTEBOOK_PRICE, "office", true);
        CatalogFixtures.addProduct(jdbc, RETIRED_PEN, "Retired Pen", "Gone", 200, "office", false);
        lampId = CatalogFixtures.idOf(jdbc, LAMP);
        notebookId = CatalogFixtures.idOf(jdbc, NOTEBOOK);
        retiredPenId = CatalogFixtures.idOf(jdbc, RETIRED_PEN);
        CatalogFixtures.setStock(jdbc, lampId, 10, 0);
        CatalogFixtures.setStock(jdbc, notebookId, 10, 0);
        CatalogFixtures.setStock(jdbc, retiredPenId, 10, 0);
    }

    protected AdminClient customer(String subject) {
        return new AdminClient(signedInAs(Identity.customer().withSubject(subject)));
    }

    protected static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return AdminClient.json(response);
    }

    protected JsonNode putLine(AdminClient client, String slug, int quantity) {
        return ok(client.put("/api/cart/lines/" + slug, "{\"quantity\":" + quantity + "}"));
    }

    protected JsonNode cart(AdminClient client) {
        return ok(client.get("/api/cart"));
    }

    protected HttpResponse<String> checkout(AdminClient client, UUID key, long cartVersion, long expectedTotal) {
        return client.post(
                "/api/checkout",
                "{\"cartVersion\":" + cartVersion + ",\"expectedTotalMinor\":" + expectedTotal + "}",
                "Idempotency-Key",
                key.toString());
    }

    /** Checks out the cart exactly as the customer sees it. */
    protected HttpResponse<String> checkoutCurrentCart(AdminClient client, UUID key) {
        JsonNode cart = cart(client);
        return checkout(
                client,
                key,
                cart.get("version").asLong(),
                cart.get("total").get("amountMinor").asLong());
    }

    protected JsonNode placeOrder(AdminClient client) {
        HttpResponse<String> response = checkoutCurrentCart(client, UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return AdminClient.json(response);
    }

    protected JsonNode placeOrderFor(AdminClient client, String slug, int quantity) {
        putLine(client, slug, quantity);
        return placeOrder(client);
    }

    /** Writes a pending order and its holds straight into the tables, lines in the given order. */
    protected UUID insertPendingOrder(String subject, Instant expiresAt, long[]... productAndQuantity) {
        UUID id = insertOrderRow(subject, expiresAt);
        addHolds(id, expiresAt, productAndQuantity);
        return id;
    }

    protected UUID insertOrderRow(String subject, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO orders (id, customer_subject, status, total_minor, currency, created_at, expires_at)
                        VALUES (:id, :subject, 'PENDING_PAYMENT', 0, 'EUR', :created, :expires)
                        """)
                .param("id", id)
                .param("subject", subject)
                .param("created", expiresAt.minusSeconds(900).atOffset(ZoneOffset.UTC))
                .param("expires", expiresAt.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private void addHolds(UUID id, Instant expiresAt, long[]... productAndQuantity) {
        int lineNo = 1;
        for (long[] line : productAndQuantity) {
            jdbc.sql("""
                            INSERT INTO order_line (order_id, line_no, product_id, product_slug, product_name,
                                                    unit_price_minor, currency, quantity)
                            SELECT :id, :lineNo, id, slug, name, price_minor, currency, :quantity FROM product WHERE id = :product
                            """)
                    .param("id", id)
                    .param("lineNo", lineNo++)
                    .param("product", line[0])
                    .param("quantity", (int) line[1])
                    .update();
            jdbc.sql("""
                            INSERT INTO reservation (order_id, product_id, quantity, status, created_at)
                            VALUES (:id, :product, :quantity, 'ACTIVE', :created)
                            """)
                    .param("id", id)
                    .param("product", line[0])
                    .param("quantity", (int) line[1])
                    .param("created", expiresAt.atOffset(ZoneOffset.UTC))
                    .update();
            jdbc.sql("UPDATE stock SET reserved = reserved + :quantity WHERE product_id = :product")
                    .param("product", line[0])
                    .param("quantity", (int) line[1])
                    .update();
        }
    }

    protected String orderStatus(Object orderId) {
        return jdbc.sql("SELECT status FROM orders WHERE id = :id")
                .param("id", UUID.fromString(orderId.toString()))
                .query(String.class)
                .single();
    }

    protected int reserved(long productId) {
        return jdbc.sql("SELECT reserved FROM stock WHERE product_id = :id")
                .param("id", productId)
                .query(Integer.class)
                .single();
    }

    /** reserved always equals the sum of active reservations, and stays between 0 and on_hand. */
    protected void assertStockInvariant() {
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM stock s
                                WHERE s.reserved < 0 OR s.reserved > s.on_hand
                                   OR s.reserved <> coalesce((SELECT sum(r.quantity) FROM reservation r
                                                              WHERE r.product_id = s.product_id AND r.status = 'ACTIVE'), 0)
                                """).query(Integer.class).single())
                .as("stock rows breaking the reserved invariant")
                .isZero();
    }

    protected static void assertProblem(HttpResponse<String> response, int status, String type, String... fields) {
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
                                        .map(error -> error.get("field").asString()))
                .containsExactlyInAnyOrder(fields);
    }

    protected static String type(String name) {
        return "tag:shop.dev,2026:" + name;
    }
}
