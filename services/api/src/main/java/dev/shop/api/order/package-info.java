/** Checkout, orders and the jobs that expire unpaid ones: the only module allowed to hold or release stock. */
@ApplicationModule(
        displayName = "order",
        allowedDependencies = {"cart", "catalog::pricing", "inventory::reservations", "customer", "web"})
package dev.shop.api.order;

import org.springframework.modulith.ApplicationModule;
