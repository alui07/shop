package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OtlpLogAppenderConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OtlpLogAppenderConfiguration.class)
            .withBean(OpenTelemetry.class, OpenTelemetry::noop);

    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @Test
    void nothingIsAttachedWhileLogExportIsOff() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(OtlpLogAppenderConfiguration.OtlpLogAppenderAttachment.class);
            assertThat(rootLogger.getAppender(OtlpLogAppenderConfiguration.APPENDER_NAME))
                    .isNull();
        });
    }

    @Test
    void nothingIsAttachedWhenLogExportIsSwitchedOffExplicitly() {
        runner.withPropertyValues("management.logging.export.otlp.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(OtlpLogAppenderConfiguration.OtlpLogAppenderAttachment.class));
    }

    @Test
    void theAppenderIsStartedAndAttachedWhileTheContextRunsAndGoneAfterItCloses() {
        runner.withPropertyValues("management.logging.export.otlp.enabled=true")
                .run(context -> assertThat(rootLogger.getAppender(OtlpLogAppenderConfiguration.APPENDER_NAME))
                        .isNotNull()
                        .matches(appender -> appender.isStarted()));

        assertThat(rootLogger.getAppender(OtlpLogAppenderConfiguration.APPENDER_NAME))
                .isNull();
    }
}
