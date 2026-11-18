package dev.shop.api.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProductRepository {

    private static final String ACTIVE_PRODUCTS =
            " FROM product p JOIN category c ON c.id = p.category_id WHERE p.active";
    private static final String TEXT_FILTER = " AND p.search_vector @@ websearch_to_tsquery('english', :q)";
    private static final String CATEGORY_FILTER = " AND c.slug = :category";
    private static final String SUMMARY_COLUMNS =
            "SELECT p.slug, p.name, p.price_minor, p.currency, c.slug AS category_slug, c.name AS category_name";

    private final JdbcClient jdbc;

    ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Category> findCategories() {
        return jdbc.sql("SELECT slug, name FROM category ORDER BY name, slug")
                .query((rs, rowNum) -> new Category(rs.getString("slug"), rs.getString("name")))
                .list();
    }

    List<ProductSummary> findPage(ProductSearch search) {
        Map<String, Object> parameters = filterParameters(search);
        parameters.put("limit", search.size());
        parameters.put("offset", search.offset());
        return jdbc.sql(SUMMARY_COLUMNS + filters(search) + " ORDER BY "
                        + search.sort().orderBy() + " LIMIT :limit OFFSET :offset")
                .params(parameters)
                .query(ProductRepository::mapSummary)
                .list();
    }

    long count(ProductSearch search) {
        return jdbc.sql("SELECT count(*)" + filters(search))
                .params(filterParameters(search))
                .query(Long.class)
                .single();
    }

    Optional<ProductDetail> findBySlug(String slug) {
        return jdbc.sql("""
                        SELECT p.slug, p.name, p.description, p.price_minor, p.currency,
                               c.slug AS category_slug, c.name AS category_name
                        FROM product p JOIN category c ON c.id = p.category_id
                        WHERE p.active AND p.slug = :slug
                        """)
                .param("slug", slug)
                .query((rs, rowNum) -> new ProductDetail(
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("description"),
                        mapPrice(rs),
                        mapCategory(rs)))
                .optional();
    }

    List<AdminProduct> findAllPage(String filter, int page, int size) {
        Map<String, Object> parameters = nameOrSlugParameters(filter);
        parameters.put("limit", size);
        parameters.put("offset", (long) page * size);
        return jdbc.sql("""
                        SELECT p.id, p.slug, p.name, p.description, p.price_minor, p.currency, p.active,
                               c.slug AS category_slug, c.name AS category_name
                        FROM product p JOIN category c ON c.id = p.category_id
                        WHERE :filter::text IS NULL OR position(lower(:filter) in lower(p.name)) > 0
                              OR position(lower(:filter) in p.slug) > 0
                        ORDER BY lower(p.name), p.id LIMIT :limit OFFSET :offset
                        """)
                .params(parameters)
                .query((rs, rowNum) -> new AdminProduct(
                        rs.getLong("id"),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("description"),
                        mapPrice(rs),
                        mapCategory(rs),
                        rs.getBoolean("active")))
                .list();
    }

    long countAll(String filter) {
        return jdbc.sql("""
                        SELECT count(*) FROM product p
                        WHERE :filter::text IS NULL OR position(lower(:filter) in lower(p.name)) > 0
                              OR position(lower(:filter) in p.slug) > 0
                        """)
                .params(nameOrSlugParameters(filter))
                .query(Long.class)
                .single();
    }

    private static Map<String, Object> nameOrSlugParameters(String filter) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("filter", filter);
        return parameters;
    }

    private static String filters(ProductSearch search) {
        return ACTIVE_PRODUCTS + (search.hasText() ? TEXT_FILTER : "") + (search.hasCategory() ? CATEGORY_FILTER : "");
    }

    private static Map<String, Object> filterParameters(ProductSearch search) {
        Map<String, Object> parameters = new HashMap<>();
        if (search.hasText()) {
            parameters.put("q", search.text());
        }
        if (search.hasCategory()) {
            parameters.put("category", search.categorySlug());
        }
        return parameters;
    }

    private static ProductSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new ProductSummary(rs.getString("slug"), rs.getString("name"), mapPrice(rs), mapCategory(rs));
    }

    private static Money mapPrice(ResultSet rs) throws SQLException {
        return new Money(rs.getLong("price_minor"), rs.getString("currency"));
    }

    private static Category mapCategory(ResultSet rs) throws SQLException {
        return new Category(rs.getString("category_slug"), rs.getString("category_name"));
    }
}
