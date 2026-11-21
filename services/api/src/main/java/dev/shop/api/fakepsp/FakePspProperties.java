package dev.shop.api.fakepsp;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** {@code webhookUrl} is blank everywhere but tests: the sender then calls the server's own port. */
@Validated
@ConfigurationProperties("shop.payment.fake")
record FakePspProperties(
        @NotNull @DefaultValue("5m") Duration sessionGrace,
        @DefaultValue("") String webhookUrl) {}
