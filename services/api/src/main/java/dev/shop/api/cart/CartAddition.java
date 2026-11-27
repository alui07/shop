package dev.shop.api.cart;

/** The cart after an addition; replayed is true when an earlier request with the same key had already made it. */
record CartAddition(Cart cart, boolean replayed) {}
