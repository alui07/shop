package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitRuleTest {

    private static Optional<RateLimitRule> ruleFor(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        return Arrays.stream(RateLimitRule.values())
                .filter(rule -> rule.matcher().matches(request))
                .findFirst();
    }

    static Stream<Arguments> limitedRequests() {
        return Stream.of(
                Arguments.of("POST", "/api/checkout", RateLimitRule.CHECKOUT),
                Arguments.of("POST", "/api/orders/abc/payment", RateLimitRule.ORDER_ACTIONS),
                Arguments.of("POST", "/api/orders/abc/cancel", RateLimitRule.ORDER_ACTIONS),
                Arguments.of("PUT", "/api/cart/lines/desk-lamp", RateLimitRule.CART_WRITES),
                Arguments.of("DELETE", "/api/cart/lines/desk-lamp", RateLimitRule.CART_WRITES),
                Arguments.of("POST", "/api/cart/lines/x/additions", RateLimitRule.CART_WRITES),
                Arguments.of("POST", "/api/cart", RateLimitRule.CART_WRITES),
                Arguments.of("PATCH", "/api/cart/anything/else", RateLimitRule.CART_WRITES),
                Arguments.of("POST", "/api/admin/products", RateLimitRule.ADMIN_WRITES),
                Arguments.of("PUT", "/api/admin/products/1", RateLimitRule.ADMIN_WRITES),
                Arguments.of("DELETE", "/api/admin/products/1", RateLimitRule.ADMIN_WRITES));
    }

    @ParameterizedTest(name = "{0} {1} is {2}")
    @MethodSource("limitedRequests")
    void theRuleTheRequestFallsUnder(String method, String path, RateLimitRule expected) {
        assertThat(ruleFor(method, path)).contains(expected);
    }

    static Stream<Arguments> unlimitedRequests() {
        return Stream.of(
                Arguments.of("GET", "/api/cart"),
                Arguments.of("HEAD", "/api/cart"),
                Arguments.of("OPTIONS", "/api/cart/lines/x"),
                Arguments.of("TRACE", "/api/admin/products"),
                Arguments.of("GET", "/api/admin/audit"),
                Arguments.of("GET", "/api/checkout"),
                Arguments.of("PUT", "/api/checkout"),
                Arguments.of("GET", "/api/orders/abc/cancel"),
                Arguments.of("POST", "/api/orders/abc"),
                Arguments.of("POST", "/api/orders/abc/def/payment"),
                Arguments.of("POST", "/api/payments/webhook"),
                Arguments.of("POST", "/oauth2/authorization/keycloak"));
    }

    @ParameterizedTest(name = "{0} {1} is not limited")
    @MethodSource("unlimitedRequests")
    void requestsNoRuleCovers(String method, String path) {
        assertThat(ruleFor(method, path)).isEmpty();
    }

    /** The security chain's matcher does not see these as the limited routes either, so none reaches the controller. */
    @ParameterizedTest
    @MethodSource("otherSpellings")
    void otherSpellingsOfALimitedRouteAreNotTheRoute(String method, String path) {
        assertThat(ruleFor(method, path)).isEmpty();
    }

    static Stream<Arguments> otherSpellings() {
        return Stream.of(
                Arguments.of("POST", "/api/checkout/"),
                Arguments.of("POST", "/API/checkout"),
                Arguments.of("POST", "/api//checkout"),
                Arguments.of("POST", "/api/orders/abc/cancel/"));
    }

    /** The decoded segment and the path parameter are what Spring MVC routes on too, so they must count. */
    @ParameterizedTest
    @MethodSource("sameRouteSpellings")
    void spellingsSpringRoutesToTheSameControllerAreCountedTheSame(String method, String path) {
        assertThat(ruleFor(method, path)).contains(RateLimitRule.CHECKOUT);
    }

    static Stream<Arguments> sameRouteSpellings() {
        return Stream.of(Arguments.of("POST", "/api/%63heckout"), Arguments.of("POST", "/api/checkout;x=1"));
    }

    @Test
    void anOpenApiTemplatePathIsMatchedByTheSamePatterns() {
        assertThat(RateLimitRule.ORDER_ACTIONS.appliesTo("POST", "/api/orders/{id}/cancel"))
                .isTrue();
        assertThat(RateLimitRule.ORDER_ACTIONS.appliesTo("GET", "/api/orders/{id}/cancel"))
                .isFalse();
        assertThat(RateLimitRule.CART_WRITES.appliesTo("DELETE", "/api/cart/lines/{slug}"))
                .isTrue();
        assertThat(RateLimitRule.CART_WRITES.appliesTo("GET", "/api/cart/lines/{slug}"))
                .isFalse();
        assertThat(RateLimitRule.CHECKOUT.appliesTo("POST", "/api/orders")).isFalse();
    }

    @Test
    void everyRuleHasAStableNameAndALimitPerWindow() {
        assertThat(Arrays.stream(RateLimitRule.values()).map(RateLimitRule::ruleName))
                .containsExactly("checkout", "order-actions", "cart-writes", "admin-writes");
        assertThat(RateLimitRule.CHECKOUT.limits()).hasSize(2);
        assertThat(RateLimitRule.CHECKOUT.describe()).isEqualTo("/api/checkout");
        assertThat(RateLimitRule.ORDER_ACTIONS.describe()).contains("/payment").contains("/cancel");
    }
}
