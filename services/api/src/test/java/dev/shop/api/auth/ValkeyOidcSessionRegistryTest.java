package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.oidc.authentication.logout.OidcLogoutToken;

class ValkeyOidcSessionRegistryTest {

    @Test
    @SuppressWarnings("unchecked")
    void aSessionRemovedByAConcurrentLogoutBetweenLookupAndRemovalIsSkipped() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForHash()).thenReturn(hashes);
        when(sets.members("shop:oidc:sid:sid-1")).thenReturn(Set.of("session-1"));
        Map<Object, Object> record = Map.of("sub", "user-1", "sid", "sid-1", "iss", "https://idp/realms/shop");
        when(hashes.entries("shop:oidc:session:session-1")).thenReturn(record, Map.of());
        OidcLogoutToken token = OidcLogoutToken.withTokenValue("t")
                .issuer("https://idp/realms/shop")
                .audience(java.util.List.of("shop-api"))
                .subject("user-1")
                .sessionId("sid-1")
                .issuedAt(java.time.Instant.now())
                .claims(claims -> claims.put("jti", "j"))
                .build();
        ValkeyOidcSessionRegistry registry = new ValkeyOidcSessionRegistry(redis, propertiesWithAbsolute());

        assertThat(registry.removeSessionInformation(token)).isEmpty();
    }

    private static ShopAuthProperties propertiesWithAbsolute() {
        return new ShopAuthProperties(
                "https://shop.example.test",
                "https://idp/realms/shop",
                "http://kc/realms/shop",
                "shop-api",
                "secret",
                Duration.ofHours(8),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Duration.ofMinutes(15));
    }
}
