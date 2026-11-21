package dev.shop.api.inventory.reservations;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReservationRepository {

    private final JdbcClient jdbc;

    ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The single statement that decides whether a unit can be held: it only succeeds while enough is free. */
    boolean reserveIfAvailable(long productId, int quantity) {
        return jdbc.sql("""
                        UPDATE stock SET reserved = reserved + :quantity
                        WHERE product_id = :id AND on_hand - reserved >= :quantity
                        """).param("id", productId).param("quantity", quantity).update() == 1;
    }

    boolean isAvailable(long productId, int quantity) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM stock WHERE product_id = :id AND on_hand - reserved >= :quantity)")
                .param("id", productId)
                .param("quantity", quantity)
                .query(Boolean.class)
                .single();
    }

    void insertActive(UUID orderId, ReservationLine line, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO reservation (order_id, product_id, quantity, status, created_at)
                        VALUES (:order, :product, :quantity, 'ACTIVE', :now)
                        """)
                .param("order", orderId)
                .param("product", line.productId())
                .param("quantity", line.quantity())
                .param("now", now)
                .update();
    }

    /** Active quantity per product for these orders, in ascending product id: the order stock rows are locked in. */
    Map<Long, Integer> activeQuantityByProduct(Collection<UUID> orderIds) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT product_id, sum(quantity)::int AS quantity FROM reservation
                        WHERE order_id IN (:orders) AND status = 'ACTIVE'
                        GROUP BY product_id ORDER BY product_id
                        """).param("orders", orderIds).query(rs -> {
            quantities.put(rs.getLong("product_id"), rs.getInt("quantity"));
        });
        return quantities;
    }

    void giveBack(long productId, int quantity) {
        jdbc.sql("UPDATE stock SET reserved = reserved - :quantity WHERE product_id = :id")
                .param("id", productId)
                .param("quantity", quantity)
                .update();
    }

    void markReleased(Collection<UUID> orderIds) {
        jdbc.sql("UPDATE reservation SET status = 'RELEASED' WHERE order_id IN (:orders) AND status = 'ACTIVE'")
                .param("orders", orderIds)
                .update();
    }

    void consumeStock(long productId, int quantity) {
        jdbc.sql("""
                        UPDATE stock SET on_hand = on_hand - :quantity, reserved = reserved - :quantity,
                                         version = version + 1
                        WHERE product_id = :id
                        """).param("id", productId).param("quantity", quantity).update();
    }

    void markConsumed(UUID orderId) {
        jdbc.sql("UPDATE reservation SET status = 'CONSUMED' WHERE order_id = :order AND status = 'ACTIVE'")
                .param("order", orderId)
                .update();
    }

    /** Locks the stock rows in ascending product id and returns what is free on each. */
    Map<Long, Integer> lockFreeStock(Collection<Long> productIds) {
        Map<Long, Integer> free = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT product_id, on_hand - reserved AS free FROM stock
                        WHERE product_id IN (:ids) ORDER BY product_id FOR UPDATE
                        """).param("ids", productIds).query(rs -> {
            free.put(rs.getLong("product_id"), rs.getInt("free"));
        });
        return free;
    }

    void takeStock(long productId, int quantity) {
        jdbc.sql("UPDATE stock SET on_hand = on_hand - :quantity, version = version + 1 WHERE product_id = :id")
                .param("id", productId)
                .param("quantity", quantity)
                .update();
    }

    void insertConsumed(UUID orderId, long productId, int quantity, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO reservation (order_id, product_id, quantity, status, created_at)
                        VALUES (:order, :product, :quantity, 'CONSUMED', :now)
                        """)
                .param("order", orderId)
                .param("product", productId)
                .param("quantity", quantity)
                .param("now", now)
                .update();
    }
}
