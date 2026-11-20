package dev.shop.api.catalog.pricing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Prices as other modules need them. Inactive products are included: the callers decide what that means. */
@Service
public class ProductPricing {

    private static final String COLUMNS = "SELECT id, slug, name, price_minor, currency, active FROM product";

    private final JdbcClient jdbc;

    ProductPricing(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** No locks; the empty collection needs no query. */
    @Transactional(readOnly = true)
    public List<PricedProduct> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(COLUMNS + " WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids)
                .query(ProductPricing::map)
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<PricedProduct> findBySlug(String slug) {
        return jdbc.sql(COLUMNS + " WHERE slug = :slug")
                .param("slug", slug)
                .query(ProductPricing::map)
                .optional();
    }

    /**
     * Share-locks the rows in ascending id order, so an admin price change waits for the checkout that read the old
     * price and the order's snapshot equals the price at commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PricedProduct> lockForCheckout(Collection<Long> ids) {
        return jdbc.sql(COLUMNS + " WHERE id IN (:ids) ORDER BY id FOR SHARE")
                .param("ids", ids)
                .query(ProductPricing::map)
                .list();
    }

    private static PricedProduct map(ResultSet rs, int rowNum) throws SQLException {
        return new PricedProduct(
                rs.getLong("id"),
                rs.getString("slug"),
                rs.getString("name"),
                new Money(rs.getLong("price_minor"), rs.getString("currency")),
                rs.getBoolean("active"));
    }
}
