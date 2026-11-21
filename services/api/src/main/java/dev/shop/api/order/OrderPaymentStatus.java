package dev.shop.api.order;

/** The status of the order's latest payment. */
enum OrderPaymentStatus {
    CREATED,
    SUCCEEDED,
    FAILED,
    REFUND_PENDING,
    REFUNDED
}
