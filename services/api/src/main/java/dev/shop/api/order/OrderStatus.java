package dev.shop.api.order;

import java.time.Instant;

enum OrderStatus {
    PENDING_PAYMENT,
    CANCELLED,
    EXPIRED,
    PAID,
    REFUND_PENDING,
    REFUNDED;

    /** An unpaid order past its hold reads as expired before the expiry job has written that down. */
    OrderStatus presentedAt(Instant expiresAt, Instant now) {
        return this == PENDING_PAYMENT && !expiresAt.isAfter(now) ? EXPIRED : this;
    }
}
