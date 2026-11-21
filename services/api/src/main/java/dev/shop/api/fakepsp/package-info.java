/** A stand-in payment provider for local, test and demo stacks: a hosted page and a signed webhook sender. */
@ApplicationModule(
        displayName = "fakepsp",
        allowedDependencies = {"payment", "web"})
package dev.shop.api.fakepsp;

import org.springframework.modulith.ApplicationModule;
