package dev.shop.api.order;

/** The facts a transition is decided on, worked out by the caller under the order lock. */
enum OrderTrigger {
    CANCEL,
    EXPIRE,
    PAY_IN_TIME,
    PAY_LATE_TAKEABLE,
    PAY_LATE_NOT_TAKEABLE,
    PAY_MISMATCH,
    PAY_FAILED,
    PAY_OTHER_PAYMENT,
    REFUND_DONE,
    REFUND_DONE_PARTIAL
}
