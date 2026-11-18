/** Authentication: Keycloak login, server-side sessions, CSRF, both logouts and the security filter chain. */
@ApplicationModule(displayName = "auth", allowedDependencies = "customer")
package dev.shop.api.auth;

import org.springframework.modulith.ApplicationModule;
