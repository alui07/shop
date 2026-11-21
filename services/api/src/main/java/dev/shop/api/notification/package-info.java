/** Emails about orders. Reads orders only through the read-only summaries, so it cannot change one. */
@ApplicationModule(
        displayName = "notification",
        allowedDependencies = {"order::summaries", "web"})
package dev.shop.api.notification;

import org.springframework.modulith.ApplicationModule;
