package dev.shop.api.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** Points the API at the stub identity provider and gives tests control of the application clock. */
@TestConfiguration(proxyBeanMethods = false)
public class IdentityProviderTestConfiguration {

    @Bean(destroyMethod = "close")
    StubIdentityProvider stubIdentityProvider() {
        return new StubIdentityProvider();
    }

    @Bean
    DynamicPropertyRegistrar identityProviderProperties(StubIdentityProvider provider) {
        return registry -> registry.add("shop.auth.internal-realm-url", provider::internalRealmUrl);
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
