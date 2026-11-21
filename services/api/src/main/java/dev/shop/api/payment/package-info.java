/** Starting a payment with the provider and receiving the provider's signed webhook. */
@ApplicationModule(
        displayName = "payment",
        allowedDependencies = {"order::payments", "customer", "web"})
package dev.shop.api.payment;

import org.springframework.modulith.ApplicationModule;
