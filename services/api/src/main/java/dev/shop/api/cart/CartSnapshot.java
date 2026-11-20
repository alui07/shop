package dev.shop.api.cart;

import java.util.List;

/** The locked cart as checkout sees it: lines in ascending product id. */
public record CartSnapshot(long cartId, long version, List<Line> lines) {

    public record Line(long productId, int quantity) {}
}
