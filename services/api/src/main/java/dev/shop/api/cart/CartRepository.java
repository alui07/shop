package dev.shop.api.cart;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CartRepository {

    record CartRow(long id, long version) {}

    record LineRow(long productId, int quantity) {}

    private final JdbcClient jdbc;

    CartRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<CartRow> find(String subject) {
        return jdbc.sql("SELECT id, version FROM cart WHERE customer_subject = :subject")
                .param("subject", subject)
                .query(CartRepository::mapCart)
                .optional();
    }

    Optional<CartRow> lock(String subject) {
        return jdbc.sql("SELECT id, version FROM cart WHERE customer_subject = :subject FOR UPDATE")
                .param("subject", subject)
                .query(CartRepository::mapCart)
                .optional();
    }

    /** Creates the cart or takes the lock on the existing one, whichever applies; a new cart starts at version 0. */
    CartRow lockOrCreate(String subject, OffsetDateTime now) {
        return jdbc.sql("""
                        INSERT INTO cart (customer_subject, version, updated_at) VALUES (:subject, 0, :now)
                        ON CONFLICT (customer_subject) DO UPDATE SET updated_at = EXCLUDED.updated_at
                        RETURNING id, version
                        """)
                .param("subject", subject)
                .param("now", now)
                .query(CartRepository::mapCart)
                .single();
    }

    List<LineRow> lines(long cartId) {
        return jdbc.sql("""
                        SELECT product_id, quantity FROM cart_line WHERE cart_id = :cart
                        ORDER BY added_at, product_id
                        """)
                .param("cart", cartId)
                .query((rs, rowNum) -> new LineRow(rs.getLong("product_id"), rs.getInt("quantity")))
                .list();
    }

    List<LineRow> linesByProduct(long cartId) {
        return jdbc.sql("SELECT product_id, quantity FROM cart_line WHERE cart_id = :cart ORDER BY product_id")
                .param("cart", cartId)
                .query((rs, rowNum) -> new LineRow(rs.getLong("product_id"), rs.getInt("quantity")))
                .list();
    }

    void upsertLine(long cartId, long productId, int quantity, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO cart_line (cart_id, product_id, quantity, added_at)
                        VALUES (:cart, :product, :quantity, :now)
                        ON CONFLICT (cart_id, product_id) DO UPDATE SET quantity = EXCLUDED.quantity
                        """)
                .param("cart", cartId)
                .param("product", productId)
                .param("quantity", quantity)
                .param("now", now)
                .update();
    }

    boolean deleteLine(long cartId, long productId) {
        return jdbc.sql("DELETE FROM cart_line WHERE cart_id = :cart AND product_id = :product")
                        .param("cart", cartId)
                        .param("product", productId)
                        .update()
                > 0;
    }

    void deleteLines(long cartId) {
        jdbc.sql("DELETE FROM cart_line WHERE cart_id = :cart")
                .param("cart", cartId)
                .update();
    }

    void bumpVersion(long cartId, OffsetDateTime now) {
        jdbc.sql("UPDATE cart SET version = version + 1, updated_at = :now WHERE id = :cart")
                .param("cart", cartId)
                .param("now", now)
                .update();
    }

    private static CartRow mapCart(ResultSet rs, int rowNum) throws SQLException {
        return new CartRow(rs.getLong("id"), rs.getLong("version"));
    }
}
