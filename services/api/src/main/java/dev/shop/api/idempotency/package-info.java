/** Idempotency keys shared by every customer operation that must not run twice: checkout and cart additions. */
@ApplicationModule(
        displayName = "idempotency",
        allowedDependencies = {"web"})
package dev.shop.api.idempotency;

import org.springframework.modulith.ApplicationModule;
