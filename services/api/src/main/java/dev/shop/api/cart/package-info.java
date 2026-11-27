/** A signed-in customer's cart: lines with a quantity, kept in the database under the customer's subject. */
@ApplicationModule(
        displayName = "cart",
        allowedDependencies = {"catalog::pricing", "idempotency", "inventory", "customer", "web"})
package dev.shop.api.cart;

import org.springframework.modulith.ApplicationModule;
