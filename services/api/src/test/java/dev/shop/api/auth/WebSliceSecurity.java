package dev.shop.api.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Puts the real security configuration around a {@code @WebMvcTest}, with the session store, Valkey and token
 * endpoints replaced by doubles, so slice tests prove the public endpoints stay reachable anonymously.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(WebSliceSecurityConfiguration.class)
@TestPropertySource(
        properties = {
            "shop.auth.public-origin=https://shop.example.test",
            "shop.auth.issuer=https://idp.example.test/realms/shop",
            "shop.auth.internal-realm-url=http://localhost:1/realms/shop",
            "shop.auth.client-id=shop-api",
            "shop.auth.client-secret=test-client-secret-test-client-secret"
        })
public @interface WebSliceSecurity {}
