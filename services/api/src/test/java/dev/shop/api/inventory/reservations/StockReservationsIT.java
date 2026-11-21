package dev.shop.api.inventory.reservations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shop.api.support.CatalogFixtures;
import dev.shop.api.support.ShopIT;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class StockReservationsIT extends ShopIT {

    @Autowired
    private StockReservations reservations;

    @Autowired
    private PlatformTransactionManager transactions;

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }

    @Test
    void reservingHoldsEveryLineWithoutTouchingOnHandOrTheVersion() {
        UUID order = insertOrderRow("holder", clock.instant().plus(Duration.ofMinutes(15)));

        inTransaction(() -> reservations.reserve(
                order, List.of(new ReservationLine(notebookId, 3), new ReservationLine(lampId, 2))));

        assertThat(reserved(lampId)).isEqualTo(2);
        assertThat(reserved(notebookId)).isEqualTo(3);
        assertThat(jdbc.sql("SELECT on_hand, version FROM stock WHERE product_id = :id")
                        .param("id", lampId)
                        .query((rs, row) -> rs.getInt("on_hand") + ":" + rs.getLong("version"))
                        .single())
                .isEqualTo("10:0");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation WHERE order_id = :id AND status = 'ACTIVE'")
                        .param("id", order)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertStockInvariant();
    }

    @Test
    void aShortLineNamesItselfAndEveryLaterShortLineAndHoldsNothing() {
        UUID order = insertOrderRow("short", clock.instant().plus(Duration.ofMinutes(15)));
        CatalogFixtures.setStock(jdbc, lampId, 5, 0);
        CatalogFixtures.setStock(jdbc, notebookId, 1, 0);
        CatalogFixtures.setStock(jdbc, retiredPenId, 1, 0);
        List<ReservationLine> lines = List.of(
                new ReservationLine(retiredPenId, 2),
                new ReservationLine(lampId, 1),
                new ReservationLine(notebookId, 2));

        assertThatThrownBy(() -> inTransaction(() -> reservations.reserve(order, lines)))
                .isInstanceOfSatisfying(
                        InsufficientStockException.class,
                        failure -> assertThat(failure.shortProductIds()).containsExactly(notebookId, retiredPenId));

        assertThat(reserved(lampId)).isZero();
        assertThat(reserved(notebookId)).isZero();
        assertStockInvariant();
    }

    @Test
    void aShortLineIsNotJoinedByLaterLinesThatAreAvailable() {
        UUID order = insertOrderRow("short-first", clock.instant().plus(Duration.ofMinutes(15)));
        CatalogFixtures.setStock(jdbc, lampId, 1, 0);

        assertThatThrownBy(() -> inTransaction(() -> reservations.reserve(
                        order, List.of(new ReservationLine(lampId, 2), new ReservationLine(notebookId, 2)))))
                .isInstanceOfSatisfying(
                        InsufficientStockException.class,
                        failure -> assertThat(failure.shortProductIds()).containsExactly(lampId));
    }

    @Test
    void releaseAllGivesBackEveryOrdersHoldsAndMarksThemReleased() {
        Duration none = Duration.ZERO;
        UUID first = insertPendingOrder(
                "release-a", clock.instant().plus(none), new long[] {lampId, 2}, new long[] {notebookId, 1});
        UUID second = insertPendingOrder(
                "release-b", clock.instant().plus(none), new long[] {notebookId, 3}, new long[] {lampId, 1});
        UUID untouched = insertPendingOrder("release-c", clock.instant().plus(none), new long[] {lampId, 4});

        inTransaction(() -> reservations.releaseAll(List.of(first, second)));

        assertThat(reserved(lampId)).isEqualTo(4);
        assertThat(reserved(notebookId)).isZero();
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE order_id = :id")
                        .param("id", untouched)
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
        assertStockInvariant();
    }

    @Test
    void releasingOneOrderTwiceGivesBackOnlyOnce() {
        UUID order = insertPendingOrder("twice", clock.instant(), new long[] {lampId, 2});

        inTransaction(() -> reservations.release(order));
        inTransaction(() -> reservations.release(order));

        assertThat(reserved(lampId)).isZero();
        assertStockInvariant();
    }

    @Test
    void consumingTakesTheHeldUnitsOutOfStockAndBumpsTheVersion() {
        UUID order = insertPendingOrder(
                "consumer", clock.instant().plus(Duration.ofMinutes(15)), new long[] {lampId, 2}, new long[] {
                    notebookId, 3
                });

        inTransaction(() -> reservations.consume(order));

        assertThat(onHandAndReserved(lampId)).isEqualTo("8:0:1");
        assertThat(onHandAndReserved(notebookId)).isEqualTo("7:0:1");
        assertThat(jdbc.sql("SELECT count(*) FROM reservation WHERE order_id = :id AND status = 'CONSUMED'")
                        .param("id", order)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertStockInvariant();
    }

    @Test
    void takingLateTakesEveryLineOrNothing() {
        UUID order = insertOrderRow("late", clock.instant().minusSeconds(60));
        jdbc.sql("UPDATE stock SET on_hand = 2 WHERE product_id = :id")
                .param("id", notebookId)
                .update();
        List<ReservationLine> twoLamps = List.of(new ReservationLine(lampId, 1), new ReservationLine(lampId, 1));
        List<ReservationLine> tooManyNotebooks =
                List.of(new ReservationLine(lampId, 1), new ReservationLine(notebookId, 3));

        boolean refused = inTransactionResult(() -> reservations.takeLate(order, tooManyNotebooks));
        assertThat(refused).isFalse();
        assertThat(onHandAndReserved(lampId)).isEqualTo("10:0:0");
        assertThat(onHandAndReserved(notebookId)).isEqualTo("2:0:0");

        boolean taken = inTransactionResult(() -> reservations.takeLate(order, twoLamps));
        assertThat(taken).isTrue();
        assertThat(onHandAndReserved(lampId)).isEqualTo("8:0:1");
        assertThat(jdbc.sql("SELECT quantity FROM reservation WHERE order_id = :id AND status = 'CONSUMED'")
                        .param("id", order)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertStockInvariant();
    }

    @Test
    void takingLateRefusesAProductWithNoStockRow() {
        UUID order = insertOrderRow("no-stock", clock.instant().minusSeconds(60));

        assertThat(inTransactionResult(() -> reservations.takeLate(order, List.of(new ReservationLine(987654L, 1)))))
                .isFalse();
    }

    private boolean inTransactionResult(java.util.function.Supplier<Boolean> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    private String onHandAndReserved(long productId) {
        return jdbc.sql("SELECT on_hand, reserved, version FROM stock WHERE product_id = :id")
                .param("id", productId)
                .query((rs, row) -> rs.getInt("on_hand") + ":" + rs.getInt("reserved") + ":" + rs.getLong("version"))
                .single();
    }
}
