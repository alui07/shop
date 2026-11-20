package dev.shop.api.support;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class CatalogFixtures {

    public static final int ACTIVE_PRODUCTS = 30;

    private CatalogFixtures() {}

    public static void clear(JdbcClient jdbc) {
        jdbc.sql("TRUNCATE stock, product, category, orders, cart, idempotency_key RESTART IDENTITY CASCADE")
                .update();
    }

    public static void addCategory(JdbcClient jdbc, String slug, String name) {
        jdbc.sql("INSERT INTO category (slug, name) VALUES (:slug, :name)")
                .param("slug", slug)
                .param("name", name)
                .update();
    }

    public static void addProduct(
            JdbcClient jdbc,
            String slug,
            String name,
            String description,
            long priceMinor,
            String categorySlug,
            boolean active) {
        jdbc.sql("""
                        INSERT INTO product (slug, name, description, price_minor, currency, category_id, active)
                        SELECT :slug, :name, :description, :price, 'EUR', id, :active FROM category WHERE slug = :category
                        """)
                .param("slug", slug)
                .param("name", name)
                .param("description", description)
                .param("price", priceMinor)
                .param("active", active)
                .param("category", categorySlug)
                .update();
        jdbc.sql("INSERT INTO stock (product_id, on_hand) SELECT id, 0 FROM product WHERE slug = :slug")
                .param("slug", slug)
                .update();
    }

    public static long idOf(JdbcClient jdbc, String slug) {
        return jdbc.sql("SELECT id FROM product WHERE slug = :slug")
                .param("slug", slug)
                .query(Long.class)
                .single();
    }

    /** Sets the quantity directly, the way the checkout slice will: no version bump. */
    public static void setStock(JdbcClient jdbc, long productId, int onHand, int reserved) {
        jdbc.sql("UPDATE stock SET on_hand = :onHand, reserved = :reserved WHERE product_id = :id")
                .param("id", productId)
                .param("onHand", onHand)
                .param("reserved", reserved)
                .update();
    }

    /** The audit table is append-only and never truncated; a test reads only the rows above this mark. */
    public static long auditHighWaterMark(JdbcClient jdbc) {
        return jdbc.sql("SELECT coalesce(max(id), 0) FROM admin_audit_event")
                .query(Long.class)
                .single();
    }

    /**
     * Three categories and {@link #ACTIVE_PRODUCTS} active products: 25 widgets in garden (prices repeat every
     * five), two mugs with the same name in kitchen, and three lamp-related products in office where only two have
     * "lamp" in the name. One inactive lamp is hidden everywhere.
     */
    public static void loadStandardCatalog(JdbcClient jdbc) {
        clear(jdbc);
        addCategory(jdbc, "kitchen", "Kitchen");
        addCategory(jdbc, "garden", "Garden");
        addCategory(jdbc, "office", "Office");
        for (int i = 1; i <= 25; i++) {
            addProduct(
                    jdbc,
                    String.format("widget-%02d", i),
                    String.format("Widget %02d", i),
                    "A sturdy garden widget",
                    500 + (i % 5) * 100L,
                    "garden",
                    true);
        }
        addProduct(jdbc, "twin-mug-b", "Twin Mug", "Holds coffee", 800, "kitchen", true);
        addProduct(jdbc, "twin-mug-a", "Twin Mug", "Holds tea", 800, "kitchen", true);
        addProduct(jdbc, "brass-lamp", "Brass Lamp", "A tidy desk accessory", 1000, "office", true);
        addProduct(jdbc, "table-lamp", "Table Lamp", "A tidy desk accessory", 1000, "office", true);
        addProduct(jdbc, "reading-light", "Reading Light", "Works like a lamp for books", 900, "office", true);
        addProduct(jdbc, "hidden-lamp", "Hidden Lamp", "Discontinued", 100, "office", false);
    }
}
