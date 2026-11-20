package dev.shop.api.inventory.reservations;

import java.util.List;

/** The products that cannot be reserved in the wanted quantity; the caller maps them to its own error. */
public class InsufficientStockException extends RuntimeException {

    private final List<Long> shortProductIds;

    public InsufficientStockException(List<Long> shortProductIds) {
        super("Not enough stock for " + shortProductIds.size() + " product(s)");
        this.shortProductIds = List.copyOf(shortProductIds);
    }

    public List<Long> shortProductIds() {
        return shortProductIds;
    }
}
