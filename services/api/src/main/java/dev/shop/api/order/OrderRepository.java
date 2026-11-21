package dev.shop.api.order;

import dev.shop.api.catalog.pricing.Money;
import dev.shop.api.catalog.pricing.PricedProduct;
import dev.shop.api.inventory.reservations.ReservationLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every read of a customer's order is scoped to the owner in SQL; nothing locks by id and compares afterwards. */
@Repository
class OrderRepository {

    record OrderRow(
            UUID id,
            OrderStatus status,
            Instant createdAt,
            Instant expiresAt,
            Money total,
            OrderPaymentStatus paymentStatus,
            boolean latePayment) {}

    record SummaryRow(OrderRow order, int itemCount) {}

    private static final String ORDER_COLUMNS =
            "SELECT id, status, created_at, expires_at, total_minor, currency, payment_status, late_payment FROM orders";

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(
            UUID id, String subject, String email, Money total, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        jdbc.sql("""
                        INSERT INTO orders (id, customer_subject, email, status, total_minor, currency, created_at, expires_at)
                        VALUES (:id, :subject, :email, 'PENDING_PAYMENT', :total, :currency, :createdAt, :expiresAt)
                        """)
                .param("id", id)
                .param("subject", subject)
                .param("email", email)
                .param("total", total.amountMinor())
                .param("currency", total.currency())
                .param("createdAt", createdAt)
                .param("expiresAt", expiresAt)
                .update();
    }

    void insertLine(UUID orderId, int lineNo, PricedProduct product, int quantity) {
        jdbc.sql("""
                        INSERT INTO order_line (order_id, line_no, product_id, product_slug, product_name,
                                                unit_price_minor, currency, quantity)
                        VALUES (:order, :lineNo, :product, :slug, :name, :price, :currency, :quantity)
                        """)
                .param("order", orderId)
                .param("lineNo", lineNo)
                .param("product", product.id())
                .param("slug", product.slug())
                .param("name", product.name())
                .param("price", product.price().amountMinor())
                .param("currency", product.price().currency())
                .param("quantity", quantity)
                .update();
    }

    Optional<OrderRow> findOwned(UUID id, String subject) {
        return jdbc.sql(ORDER_COLUMNS + " WHERE id = :id AND customer_subject = :subject")
                .param("id", id)
                .param("subject", subject)
                .query(OrderRepository::mapOrder)
                .optional();
    }

    Optional<OrderRow> lockOwned(UUID id, String subject) {
        return jdbc.sql(ORDER_COLUMNS + " WHERE id = :id AND customer_subject = :subject FOR UPDATE")
                .param("id", id)
                .param("subject", subject)
                .query(OrderRepository::mapOrder)
                .optional();
    }

    /** For the payment module's webhook and listeners, which run without a customer. */
    Optional<OrderRow> lockById(UUID id) {
        return jdbc.sql(ORDER_COLUMNS + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(OrderRepository::mapOrder)
                .optional();
    }

    List<ReservationLine> reservationLines(UUID orderId) {
        return jdbc.sql("SELECT product_id, quantity FROM order_line WHERE order_id = :order ORDER BY line_no")
                .param("order", orderId)
                .query((rs, rowNum) -> new ReservationLine(rs.getLong("product_id"), rs.getInt("quantity")))
                .list();
    }

    Optional<String> email(UUID orderId) {
        return jdbc.sql("SELECT email FROM orders WHERE id = :id")
                .param("id", orderId)
                .query(String.class)
                .optional();
    }

    void refreshEmail(UUID orderId, String email) {
        jdbc.sql("UPDATE orders SET email = :email WHERE id = :id")
                .param("id", orderId)
                .param("email", email)
                .update();
    }

    void setPaymentStatus(UUID orderId, OrderPaymentStatus status) {
        jdbc.sql("UPDATE orders SET payment_status = :status WHERE id = :id")
                .param("id", orderId)
                .param("status", status.name())
                .update();
    }

    void setSettledBy(UUID orderId, UUID paymentId, OrderPaymentStatus status) {
        jdbc.sql("UPDATE orders SET paid_payment_id = :payment, payment_status = :status WHERE id = :id")
                .param("id", orderId)
                .param("payment", paymentId)
                .param("status", status.name())
                .update();
    }

    void markPaid(UUID orderId, OffsetDateTime paidAt, boolean late) {
        jdbc.sql("UPDATE orders SET paid_at = :paidAt, late_payment = :late WHERE id = :id")
                .param("id", orderId)
                .param("paidAt", paidAt)
                .param("late", late)
                .update();
    }

    List<OrderLine> lines(UUID orderId) {
        return jdbc.sql("""
                        SELECT product_slug, product_name, unit_price_minor, currency, quantity
                        FROM order_line WHERE order_id = :order ORDER BY line_no
                        """)
                .param("order", orderId)
                .query((rs, rowNum) -> {
                    int quantity = rs.getInt("quantity");
                    long unit = rs.getLong("unit_price_minor");
                    String currency = rs.getString("currency");
                    return new OrderLine(
                            rs.getString("product_slug"),
                            rs.getString("product_name"),
                            new Money(unit, currency),
                            quantity,
                            new Money(unit * quantity, currency));
                })
                .list();
    }

    List<SummaryRow> page(String subject, int limit, long offset) {
        return jdbc.sql("""
                        SELECT id, status, created_at, expires_at, total_minor, currency, payment_status, late_payment,
                               (SELECT coalesce(sum(quantity), 0)::int FROM order_line WHERE order_id = orders.id)
                                   AS item_count
                        FROM orders WHERE customer_subject = :subject
                        ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                        """)
                .param("subject", subject)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> new SummaryRow(mapOrder(rs, rowNum), rs.getInt("item_count")))
                .list();
    }

    long count(String subject) {
        return jdbc.sql("SELECT count(*) FROM orders WHERE customer_subject = :subject")
                .param("subject", subject)
                .query(Long.class)
                .single();
    }

    int countPending(String subject, OffsetDateTime now) {
        return jdbc.sql("""
                        SELECT count(*) FROM orders
                        WHERE customer_subject = :subject AND status = 'PENDING_PAYMENT' AND expires_at > :now
                        """)
                .param("subject", subject)
                .param("now", now)
                .query(Integer.class)
                .single();
    }

    /** Units of each product held for this customer's unpaid orders that are still inside their hold. */
    Map<Long, Integer> heldByProduct(String subject, OffsetDateTime now, Collection<Long> productIds) {
        Map<Long, Integer> held = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT r.product_id, sum(r.quantity)::int AS quantity
                        FROM reservation r JOIN orders o ON o.id = r.order_id
                        WHERE o.customer_subject = :subject AND o.status = 'PENDING_PAYMENT' AND o.expires_at > :now
                              AND r.status = 'ACTIVE' AND r.product_id IN (:products)
                        GROUP BY r.product_id
                        """)
                .param("subject", subject)
                .param("now", now)
                .param("products", productIds)
                .query(rs -> {
                    held.put(rs.getLong("product_id"), rs.getInt("quantity"));
                });
        return held;
    }

    /** Takes the rows other replicas are not already working on. */
    List<UUID> lockExpired(OffsetDateTime now, int limit) {
        return jdbc.sql("""
                        SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND expires_at <= :now
                        ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED
                        """)
                .param("now", now)
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    void updateStatus(Collection<UUID> ids, OrderStatus status) {
        jdbc.sql("UPDATE orders SET status = :status WHERE id IN (:ids)")
                .param("status", status.name())
                .param("ids", ids)
                .update();
    }

    private static OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getObject("id", UUID.class),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                new Money(rs.getLong("total_minor"), rs.getString("currency")),
                Optional.ofNullable(rs.getString("payment_status"))
                        .map(OrderPaymentStatus::valueOf)
                        .orElse(null),
                rs.getBoolean("late_payment"));
    }
}
