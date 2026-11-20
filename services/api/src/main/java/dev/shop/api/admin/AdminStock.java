package dev.shop.api.admin;

import dev.shop.api.inventory.StockLevel;

record AdminStock(int onHand, int reserved, long version) {

    static AdminStock of(StockLevel level) {
        return new AdminStock(level.onHand(), level.reserved(), level.version());
    }
}
