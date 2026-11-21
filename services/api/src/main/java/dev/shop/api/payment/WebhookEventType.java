package dev.shop.api.payment;

import com.fasterxml.jackson.annotation.JsonValue;

enum WebhookEventType {
    PAYMENT_SUCCEEDED("payment.succeeded"),
    PAYMENT_FAILED("payment.failed");

    private final String wireName;

    WebhookEventType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    String wireName() {
        return wireName;
    }
}
