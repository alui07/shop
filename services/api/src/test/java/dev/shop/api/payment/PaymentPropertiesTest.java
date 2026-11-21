package dev.shop.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

@ExtendWith(OutputCaptureExtension.class)
class PaymentPropertiesTest {

    private static final String SECRET = "a-secret-that-is-long-enough-for-hmac";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PaymentConfiguration.class);

    @Test
    void aKnownProviderAndALongSecretStartUp() {
        runner.withPropertyValues("shop.payment.provider=fake", "shop.payment.webhook-secret=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PaymentProperties.class).webhookSecret())
                            .isEqualTo(SECRET);
                });
    }

    @Test
    void aShortSecretFailsStartupWithoutPrintingIt(CapturedOutput output) {
        String shortSecret = "twenty-chars-secret!";

        runner.withPropertyValues("shop.payment.provider=fake", "shop.payment.webhook-secret=" + shortSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = context.getStartupFailure();
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        assertThat(String.valueOf(cause.getMessage())).doesNotContain(shortSecret);
                    }
                    assertThat(rootMessage(failure)).contains("at least 32 characters");
                });
        assertThat(output.getAll()).doesNotContain(shortSecret);
    }

    @Test
    void aMissingOrUnknownProviderFailsStartup() {
        runner.withPropertyValues("shop.payment.webhook-secret=" + SECRET)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("shop.payment.provider=", "shop.payment.webhook-secret=" + SECRET)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("shop.payment.provider=stripe", "shop.payment.webhook-secret=" + SECRET)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anUnsetSecretFailsStartup() {
        runner.withPropertyValues("shop.payment.provider=fake")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aBlankSecretFailsStartup() {
        runner.withPropertyValues("shop.payment.provider=fake", "shop.payment.webhook-secret=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theSecretIsRedactedInToString() {
        assertThat(new PaymentProperties("fake", SECRET).toString())
                .doesNotContain(SECRET)
                .contains("fake");
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }
}
