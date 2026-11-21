package dev.shop.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** A real SMTP sink with an HTTP API, so the tests read the mail that was really sent. */
@TestConfiguration(proxyBeanMethods = false)
public class MailpitTestConfiguration {

    private static final String MAILPIT_IMAGE_ENV = "MAILPIT_IMAGE";

    @Bean
    MailpitContainer mailpit() {
        String image = System.getenv(MAILPIT_IMAGE_ENV);
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable " + MAILPIT_IMAGE_ENV + " must name the Mailpit image");
        }
        return new MailpitContainer(image);
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties(MailpitContainer mailpit) {
        return registry -> {
            registry.add("spring.mail.host", mailpit::getHost);
            registry.add("spring.mail.port", mailpit::smtpPort);
        };
    }
}
