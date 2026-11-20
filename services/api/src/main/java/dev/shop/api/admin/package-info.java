/** Administrator-only endpoints under /api/admin: product and stock changes, and the audit log they write. */
@ApplicationModule(
        displayName = "admin",
        allowedDependencies = {"catalog", "catalog::pricing", "inventory", "customer", "web"})
package dev.shop.api.admin;

import org.springframework.modulith.ApplicationModule;
