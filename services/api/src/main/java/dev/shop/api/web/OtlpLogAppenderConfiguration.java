package dev.shop.api.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sends log records to the OpenTelemetry SDK when log export is on. Boot provides the SDK's logger provider but no
 * logback bridge. The root logger is shared by every application context in the JVM, so the appender is attached when
 * the context starts and detached when it closes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("management.logging.export.otlp.enabled")
class OtlpLogAppenderConfiguration {

    static final String APPENDER_NAME = "OTLP";

    @Bean
    OtlpLogAppenderAttachment otlpLogAppenderAttachment(OpenTelemetry openTelemetry) {
        return new OtlpLogAppenderAttachment(openTelemetry);
    }

    static final class OtlpLogAppenderAttachment implements InitializingBean, DisposableBean {

        private final OpenTelemetryAppender appender = new OpenTelemetryAppender();
        private final OpenTelemetry openTelemetry;

        OtlpLogAppenderAttachment(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        @Override
        public void afterPropertiesSet() {
            ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
            appender.setName(APPENDER_NAME);
            appender.setContext((LoggerContext) loggerFactory);
            appender.setOpenTelemetry(openTelemetry);
            appender.start();
            rootLogger().addAppender(appender);
        }

        @Override
        public void destroy() {
            rootLogger().detachAppender(appender);
            appender.stop();
        }

        private static Logger rootLogger() {
            return ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
        }
    }
}
