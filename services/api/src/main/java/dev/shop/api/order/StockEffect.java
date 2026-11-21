package dev.shop.api.order;

/** What a transition does to the stock held for the order. */
enum StockEffect {
    NONE,
    RELEASE,
    CONSUME,
    /** Takes stock that was already given back; the only effect that can fail. */
    TAKE_LATE
}
