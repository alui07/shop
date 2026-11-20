package dev.shop.api.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.shop.api.customer.CurrentUser;
import dev.shop.api.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

/** The services refuse a caller without the customer role even if the URL rules were bypassed. */
@IntegrationTest
@WithMockUser(roles = "ADMIN")
class OrderMethodSecurityIT {

    @Autowired
    private CheckoutService checkout;

    @Autowired
    private OrderService orders;

    @Test
    void checkoutAndOrderServicesNeedTheCustomerRole() {
        assertThatThrownBy(() ->
                        checkout.checkout(new CurrentUser("s", "n"), UUID.randomUUID(), new CheckoutInput(0L, 0L)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orders.list("s", 0, 20)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orders.get("s", UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orders.cancel("s", UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
    }
}
