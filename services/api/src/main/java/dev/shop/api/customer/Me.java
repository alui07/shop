package dev.shop.api.customer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

record Me(
        boolean authenticated,
        @Schema(nullable = true) String subject,
        @Schema(nullable = true) String name,
        @Schema(nullable = true) String email,
        List<Role> roles) {

    enum Role {
        customer,
        admin
    }

    static Me anonymous() {
        return new Me(false, null, null, null, List.of());
    }
}
