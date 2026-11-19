package dev.shop.api.admin;

/** Bounds shared by the request records; the web app's schemas mirror them. */
final class Rules {

    static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";
    static final long MAX_PRICE_MINOR = 10_000_000;
    static final int MAX_ON_HAND = 1_000_000;

    private Rules() {}
}
