package dev.shop.api.payment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Where to send the customer to pay")
record PaymentStart(
        @Schema(
                description = "Relative URL of the provider's payment page, /fake-psp/{token}",
                example = "/fake-psp/abc")
        String redirectUrl) {}
