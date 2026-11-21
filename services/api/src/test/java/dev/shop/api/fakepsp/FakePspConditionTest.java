package dev.shop.api.fakepsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The fake provider ships in the binary but only exists when the provider property says so. */
class FakePspConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FakePspConfiguration.class, FakePspController.class)
            .withBean(FakePspService.class, () -> mock(FakePspService.class));

    @Test
    void thePageAndItsPropertiesExistOnlyForTheFakeProvider() {
        runner.withPropertyValues("shop.payment.provider=fake").run(context -> {
            assertThat(context).hasSingleBean(FakePspController.class);
            assertThat(context).hasSingleBean(FakePspProperties.class);
        });
        runner.withPropertyValues("shop.payment.provider=other").run(context -> {
            assertThat(context).doesNotHaveBean(FakePspController.class);
            assertThat(context).doesNotHaveBean(FakePspProperties.class);
        });
        runner.run(context -> assertThat(context).doesNotHaveBean(FakePspController.class));
    }
}
