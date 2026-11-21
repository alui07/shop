package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.shop.api.cart.CartCheckout;
import dev.shop.api.catalog.pricing.ProductPricing;
import dev.shop.api.customer.CurrentUser;
import dev.shop.api.inventory.reservations.StockReservations;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

class CheckoutServiceTest {

    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final CheckoutService service = new CheckoutService(
            idempotency,
            mock(CartCheckout.class),
            mock(ProductPricing.class),
            mock(StockReservations.class),
            mock(OrderRepository.class),
            new CheckoutProperties(
                    Duration.ofMinutes(15), 3, 10, Duration.ofHours(24), 50, 1000, Duration.ofMinutes(30)),
            Clock.systemUTC());

    private void checkout() {
        service.checkout(new CurrentUser("sub", "Name"), UUID.randomUUID(), new CheckoutInput(1L, 1L));
    }

    @Test
    void aLockTimeoutWhileClaimingTheKeyIsInProgressWhetherSpringOrOnlyTheSqlStateSaysSo() {
        when(idempotency.claim(any(), any(), any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("busy"))
                .thenThrow(new UncategorizedSQLException("insert", "INSERT", new SQLException("busy", "55P03")));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(this::checkout)
                    .isInstanceOfSatisfying(
                            OrderProblem.class,
                            problem -> assertThat(problem.getBody().getType())
                                    .hasToString("tag:shop.dev,2026:idempotency-in-progress"));
        }
    }

    @Test
    void anyOtherDatabaseFailureWhileClaimingTheKeyIsNotHidden() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("broken");
        when(idempotency.claim(any(), any(), any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(this::checkout).isSameAs(failure);
    }

    @Test
    void anUnknownDigestAlgorithmIsRefused() {
        assertThatThrownBy(() -> CheckoutService.digest("text", "NO-SUCH-DIGEST"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CheckoutService.digest("abc", "SHA-256"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
