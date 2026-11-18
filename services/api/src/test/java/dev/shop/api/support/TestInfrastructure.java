package dev.shop.api.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** The containers and settings every application context in the tests needs: Postgres, Valkey and the auth settings. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({PostgresTestConfiguration.class, ValkeyTestConfiguration.class, IdentityProviderTestConfiguration.class})
@TestPropertySource(
        properties = {
            "shop.auth.public-origin=https://shop.example.test",
            "shop.auth.issuer=https://idp.example.test/realms/shop",
            "shop.auth.internal-realm-url=http://localhost:1/realms/shop",
            "shop.auth.client-id=shop-api",
            "shop.auth.client-secret=test-client-secret-test-client-secret"
        })
public @interface TestInfrastructure {}
