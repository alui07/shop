package dev.shop.api.cart;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shop.api.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** The services refuse a caller without the customer role even if the URL rules were bypassed. */
@IntegrationTest
@WithMockUser(roles = "ADMIN")
class CartMethodSecurityIT {

    @Autowired
    private CartService carts;

    @Autowired
    private CartCheckout checkout;

    @Test
    void cartServicesNeedTheCustomerRole() {
        assertThatThrownBy(() -> carts.get("s")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> carts.putLine("s", "x", 1)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> carts.addToLine("s", UUID.randomUUID(), "x", 1))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> carts.removeLine("s", "x")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> checkout.lockForCheckout("s")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> checkout.clear(1)).isInstanceOf(AccessDeniedException.class);
    }
}
