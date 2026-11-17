package dev.shop.api.support;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class CatalogFixtures {

    public static final int ACTIVE_PRODUCTS = 30;

    private CatalogFixtures() {}

    public static void clear(JdbcClient jdbc) {
        jdbc.sql("TRUNCATE product, category RESTART IDENTITY CASCADE").update();
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
