package dev.shop.api.inventory.reservations;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds stock for orders. There is no {@code @PreAuthorize}: the expiry job runs without a user. Every method joins
 * the caller's transaction, so a failure anywhere in checkout undoes the holds too. Stock rows are always touched in
 * ascending product id, which is the lock order that keeps concurrent checkouts and expiry batches from deadlocking.
 * {@code stock.version} is not bumped: reserving never changes {@code on_hand}.
 */
@Service
public class StockReservations {

    private final ReservationRepository repository;
    private final Clock clock;

    StockReservations(ReservationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Reserves every line or throws {@link InsufficientStockException} naming the short products: the line that
     * failed, and each later line that would also fail.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID orderId, Collection<ReservationLine> lines) {
        List<ReservationLine> ascending = lines.stream()
                .sorted(Comparator.comparingLong(ReservationLine::productId))
                .toList();
        for (int i = 0; i < ascending.size(); i++) {
            ReservationLine line = ascending.get(i);
            if (!repository.reserveIfAvailable(line.productId(), line.quantity())) {
                throw new InsufficientStockException(shortProducts(line, ascending.subList(i + 1, ascending.size())));
            }
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        ascending.forEach(line -> repository.insertActive(orderId, line, now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID orderId) {
        releaseAll(List.of(orderId));
    }

    /**
     * Gives back the active holds of all these orders, adding up per product so each stock row is updated once.
     * Returns the number of units given back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int releaseAll(Collection<UUID> orderIds) {
        Map<Long, Integer> held = repository.activeQuantityByProduct(orderIds);
        held.forEach(repository::giveBack);
        repository.markReleased(orderIds);
        return held.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The order was paid inside its hold: the held units leave the shelf for good. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(UUID orderId) {
        repository.activeQuantityByProduct(List.of(orderId)).forEach(repository::consumeStock);
        repository.markConsumed(orderId);
    }

    /**
     * The order was paid after its hold was given back: takes the units now if every line is still free, else takes
     * nothing and returns false. The reservation rows only record what was taken; they are not active holds.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean takeLate(UUID orderId, Collection<ReservationLine> lines) {
        Map<Long, Integer> wanted = new TreeMap<>();
        lines.forEach(line -> wanted.merge(line.productId(), line.quantity(), Integer::sum));
        Map<Long, Integer> free = repository.lockFreeStock(wanted.keySet());
        boolean everyLineFree =
                wanted.entrySet().stream().allMatch(line -> free.getOrDefault(line.getKey(), 0) >= line.getValue());
        if (everyLineFree) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            wanted.forEach((productId, quantity) -> {
                repository.takeStock(productId, quantity);
                repository.insertConsumed(orderId, productId, quantity, now);
            });
        }
        return everyLineFree;
    }

    private List<Long> shortProducts(ReservationLine failed, List<ReservationLine> later) {
        List<Long> shortIds = new ArrayList<>(List.of(failed.productId()));
        later.stream()
                .filter(line -> !repository.isAvailable(line.productId(), line.quantity()))
                .forEach(line -> shortIds.add(line.productId()));
        return shortIds;
    }
}
