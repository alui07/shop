package dev.shop.api.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {

    record PaymentRow(
            UUID id,
            UUID orderId,
            String providerPaymentId,
            String redirectUrl,
            PaymentStatus status,
            long amountMinor,
            String currency,
            Long receivedAmountMinor,
            String receivedCurrency) {}

    private static final String COLUMNS = """
            SELECT id, order_id, provider_payment_id, redirect_url, status, amount_minor, currency,
                   received_amount_minor, received_currency FROM payment
            """;

    private final JdbcClient jdbc;

    PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertCreated(UUID id, UUID orderId, String provider, long amountMinor, String currency, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO payment (id, order_id, provider, status, amount_minor, currency, created_at, updated_at)
                        VALUES (:id, :order, :provider, 'CREATED', :amount, :currency, :now, :now)
                        """)
                .param("id", id)
                .param("order", orderId)
                .param("provider", provider)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("now", now)
                .update();
    }

    /** A separate capture of the same merchant reference: money taken that no payment of ours explains. */
    void insertRefundPending(
            UUID id,
            UUID orderId,
            String provider,
            String providerPaymentId,
            long amountMinor,
            String currency,
            OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO payment (id, order_id, provider, provider_payment_id, status, amount_minor, currency,
                                             received_amount_minor, received_currency, created_at, updated_at)
                        VALUES (:id, :order, :provider, :providerPaymentId, 'REFUND_PENDING', :amount, :currency,
                                :amount, :currency, :now, :now)
                        """)
                .param("id", id)
                .param("order", orderId)
                .param("provider", provider)
                .param("providerPaymentId", providerPaymentId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("now", now)
                .update();
    }

    record RefundTarget(String providerPaymentId, long amountMinor, String currency) {}

    /** What was actually taken: the received amount when the provider reported one, else the amount asked for. */
    RefundTarget refundTarget(UUID id) {
        return jdbc.sql("""
                        SELECT provider_payment_id, coalesce(received_amount_minor, amount_minor) AS amount,
                               coalesce(received_currency, currency) AS currency
                        FROM payment WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new RefundTarget(
                        rs.getString("provider_payment_id"), rs.getLong("amount"), rs.getString("currency")))
                .single();
    }

    Optional<PaymentRow> findOpen(UUID orderId) {
        return jdbc.sql(COLUMNS + " WHERE order_id = :order AND status = 'CREATED'")
                .param("order", orderId)
                .query(PaymentRepository::map)
                .optional();
    }

    Optional<PaymentRow> findByProviderPaymentId(String providerPaymentId) {
        return jdbc.sql(COLUMNS + " WHERE provider_payment_id = :id")
                .param("id", providerPaymentId)
                .query(PaymentRepository::map)
                .optional();
    }

    Optional<PaymentRow> findById(UUID id) {
        return jdbc.sql(COLUMNS + " WHERE id = :id")
                .param("id", id)
                .query(PaymentRepository::map)
                .optional();
    }

    Optional<PaymentRow> lockById(UUID id) {
        return jdbc.sql(COLUMNS + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(PaymentRepository::map)
                .optional();
    }

    /** Stores the provider's answer unless another start stored a different one first. */
    void setSession(UUID id, String providerPaymentId, String redirectUrl) {
        jdbc.sql("""
                        UPDATE payment SET provider_payment_id = :provider, redirect_url = :url
                        WHERE id = :id AND (provider_payment_id IS NULL OR provider_payment_id = :provider)
                        """)
                .param("id", id)
                .param("provider", providerPaymentId)
                .param("url", redirectUrl)
                .update();
    }

    void adoptProviderPaymentId(UUID id, String providerPaymentId) {
        jdbc.sql("UPDATE payment SET provider_payment_id = :provider WHERE id = :id AND provider_payment_id IS NULL")
                .param("id", id)
                .param("provider", providerPaymentId)
                .update();
    }

    void updateStatus(UUID id, PaymentStatus status, OffsetDateTime now) {
        jdbc.sql("UPDATE payment SET status = :status, updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("status", status.name())
                .param("now", now)
                .update();
    }

    void setReceived(UUID id, long amountMinor, String currency) {
        jdbc.sql("UPDATE payment SET received_amount_minor = :amount, received_currency = :currency WHERE id = :id")
                .param("id", id)
                .param("amount", amountMinor)
                .param("currency", currency)
                .update();
    }

    /** True when another payment of the order still holds the customer's money. */
    boolean otherMoneyHeld(UUID orderId, UUID exceptPaymentId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM payment WHERE order_id = :order AND id <> :except
                                       AND status IN ('SUCCEEDED', 'REFUND_PENDING'))
                        """)
                .param("order", orderId)
                .param("except", exceptPaymentId)
                .query(Boolean.class)
                .single();
    }

    /** False when the event id was seen before. */
    boolean insertWebhookEvent(String eventId, String type, OffsetDateTime now) {
        return jdbc.sql("""
                        INSERT INTO payment_webhook_event (event_id, type, received_at) VALUES (:id, :type, :now)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                        .param("id", eventId)
                        .param("type", type)
                        .param("now", now)
                        .update()
                == 1;
    }

    void insertUnmatched(String eventId, String providerPaymentId, OffsetDateTime now) {
        jdbc.sql("""
                        INSERT INTO payment_webhook_unmatched (event_id, provider_payment_id, received_at)
                        VALUES (:event, :provider, :now)
                        """)
                .param("event", eventId)
                .param("provider", providerPaymentId)
                .param("now", now)
                .update();
    }

    int deleteWebhookEventsBefore(OffsetDateTime cutoff, int limit) {
        return jdbc.sql("""
                        DELETE FROM payment_webhook_event WHERE event_id IN (
                            SELECT event_id FROM payment_webhook_event WHERE received_at < :cutoff
                            LIMIT :limit FOR UPDATE SKIP LOCKED)
                        """).param("cutoff", cutoff).param("limit", limit).update();
    }

    int deleteUnmatchedBefore(OffsetDateTime cutoff, int limit) {
        return jdbc.sql("""
                        DELETE FROM payment_webhook_unmatched WHERE id IN (
                            SELECT id FROM payment_webhook_unmatched WHERE received_at < :cutoff
                            LIMIT :limit FOR UPDATE SKIP LOCKED)
                        """).param("cutoff", cutoff).param("limit", limit).update();
    }

    private static PaymentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getString("provider_payment_id"),
                rs.getString("redirect_url"),
                PaymentStatus.valueOf(rs.getString("status")),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getObject("received_amount_minor", Long.class),
                rs.getString("received_currency"));
    }
}
