package dev.shop.api.fakepsp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "fake")
@EnableConfigurationProperties(FakePspProperties.class)
class FakePspConfiguration {}
