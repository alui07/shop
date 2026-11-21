package dev.shop.api.fakepsp;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FakePspRepository {

    record FakeRow(
            String providerPaymentId,
            String token,
            UUID merchantReference,
            long amountMinor,
            String currency,
            FakePspStatus status,
            String returnUrl,
            String eventId,
            Instant expiresAt,
            Instant completedAt) {}

    private static final String COLUMNS = """
            SELECT provider_payment_id, token, merchant_reference, amount_minor, currency, status, return_url,
                   event_id, expires_at, completed_at FROM fake_psp_payment
            """;

    private final JdbcClient jdbc;

    FakePspRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertIfAbsent(FakeRow row, OffsetDateTime createdAt) {
        jdbc.sql("""
                        INSERT INTO fake_psp_payment (provider_payment_id, token, merchant_reference, amount_minor, currency,
                                                      status, return_url, created_at, expires_at)
                        VALUES (:id, :token, :reference, :amount, :currency, 'PENDING', :returnUrl, :createdAt, :expiresAt)
                        ON CONFLICT (merchant_reference) DO NOTHING
                        """)
                .param("id", row.providerPaymentId())
                .param("token", row.token())
                .param("reference", row.merchantReference())
                .param("amount", row.amountMinor())
                .param("currency", row.currency())
                .param("returnUrl", row.returnUrl())
                .param("createdAt", createdAt)
                .param("expiresAt", row.expiresAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    Optional<FakeRow> findByMerchantReference(UUID reference) {
        return jdbc.sql(COLUMNS + " WHERE merchant_reference = :reference")
                .param("reference", reference)
                .query(FakePspRepository::map)
                .optional();
    }

    Optional<FakeRow> findByProviderPaymentId(String providerPaymentId) {
        return jdbc.sql(COLUMNS + " WHERE provider_payment_id = :id")
                .param("id", providerPaymentId)
                .query(FakePspRepository::map)
                .optional();
    }

    Optional<FakeRow> findByToken(String token) {
        return jdbc.sql(COLUMNS + " WHERE token = :token")
                .param("token", token)
                .query(FakePspRepository::map)
                .optional();
    }

    Optional<FakeRow> lockByToken(String token) {
        return jdbc.sql(COLUMNS + " WHERE token = :token FOR UPDATE")
                .param("token", token)
                .query(FakePspRepository::map)
                .optional();
    }

    void complete(String providerPaymentId, FakePspStatus status, String eventId, OffsetDateTime completedAt) {
        jdbc.sql("""
                        UPDATE fake_psp_payment SET status = :status, event_id = :event, completed_at = :completedAt
                        WHERE provider_payment_id = :id
                        """)
                .param("id", providerPaymentId)
                .param("status", status.name())
                .param("event", eventId)
                .param("completedAt", completedAt)
                .update();
    }

    void markRefunded(String providerPaymentId) {
        jdbc.sql("UPDATE fake_psp_payment SET status = 'REFUNDED' WHERE provider_payment_id = :id")
                .param("id", providerPaymentId)
                .update();
    }

    private static FakeRow map(ResultSet rs, int rowNum) throws SQLException {
        return new FakeRow(
                rs.getString("provider_payment_id"),
                rs.getString("token"),
                rs.getObject("merchant_reference", UUID.class),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                FakePspStatus.valueOf(rs.getString("status")),
                rs.getString("return_url"),
                rs.getString("event_id"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(rs.getObject("completed_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant)
                        .orElse(null));
    }
}
