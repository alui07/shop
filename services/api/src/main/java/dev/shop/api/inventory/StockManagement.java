package dev.shop.api.inventory;

import java.util.Collection;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every product has a stock row from the moment it exists. Changing {@code onHand} bumps the row's version; the
 * checkout slice changes {@code reserved} alone and leaves the version, so a checkout never fails an open admin form.
 */
@Service
public class StockManagement {

    private final StockRepository repository;

    StockManagement(StockRepository repository) {
        this.repository = repository;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation = Propagation.MANDATORY)
    public StockLevel createFor(long productId) {
        repository.insertEmpty(productId);
        return new StockLevel(0, 0, 0);
    }

    @Transactional(readOnly = true)
    public StockLevel levelOf(long productId) {
        return repository.find(productId).orElseThrow(() -> missingRow(productId));
    }

    @Transactional(readOnly = true)
    public Map<Long, StockLevel> levelsOf(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, StockLevel> levels = repository.findAll(productIds);
        productIds.stream().filter(id -> !levels.containsKey(id)).findFirst().ifPresent(id -> {
            throw missingRow(id);
        });
        return levels;
    }

    /** Locks the row, checks the version, then writes; setting the quantity it already has changes nothing. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation = Propagation.MANDATORY)
    public StockUpdate setOnHand(long productId, long expectedVersion, int onHand) {
        StockLevel before = repository.lock(productId).orElseThrow(StockNotFoundException::new);
        if (before.version() != expectedVersion) {
            throw new StockVersionConflictException();
        }
        if (onHand < before.reserved()) {
            throw new StockBelowReservedException();
        }
        if (onHand == before.onHand()) {
            return new StockUpdate(before, before);
        }
        repository.setOnHand(productId, onHand);
        return new StockUpdate(before, new StockLevel(onHand, before.reserved(), before.version() + 1));
    }

    private static IllegalStateException missingRow(long productId) {
        return new IllegalStateException("Product " + productId + " has no stock row");
    }
}
