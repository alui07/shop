package dev.shop.api.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Which provider is in use and the secret shared with it for signing webhooks. Never printed. */
@Validated
@ConfigurationProperties("shop.payment")
public record PaymentProperties(
        @NotBlank @Pattern(regexp = "fake", message = "must be fake until a real provider exists")
        String provider,

        @NotBlank String webhookSecret) {

    private static final int MIN_SECRET_LENGTH = 32;

    public PaymentProperties {
        if (webhookSecret != null && webhookSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("shop.payment.webhook-secret must be at least 32 characters");
        }
    }

    @Override
    public String toString() {
        return "PaymentProperties[provider=" + provider + ", webhookSecret=***]";
    }
}
