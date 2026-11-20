package dev.shop.api.order;

import java.time.Instant;

/** The statuses an order can reach without a payment path. */
enum OrderStatus {
    PENDING_PAYMENT,
    CANCELLED,
    EXPIRED;

    /** An unpaid order past its hold reads as expired before the expiry job has written that down. */
    OrderStatus presentedAt(Instant expiresAt, Instant now) {
        return this == PENDING_PAYMENT && !expiresAt.isAfter(now) ? EXPIRED : this;
    }
}
