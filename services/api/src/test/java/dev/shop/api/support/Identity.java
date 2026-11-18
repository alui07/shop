package dev.shop.api.support;

import java.util.List;
import java.util.UUID;

/** A Keycloak user as the stub identity provider describes them in an ID token. */
public record Identity(String subject, String name, String email, List<String> roles, String sessionId) {

    public static Identity customer() {
        return new Identity("customer-1", "Casey Customer", "customer@example.test", List.of("customer"), newSid());
    }

    public static Identity admin() {
        return new Identity("admin-1", "Alex Admin", "admin@example.test", List.of("customer", "admin"), newSid());
    }

    public Identity withRoles(List<String> newRoles) {
        return new Identity(subject, name, email, newRoles, sessionId);
    }

    public Identity withSubject(String newSubject) {
        return new Identity(newSubject, name, email, roles, sessionId);
    }

    public Identity withNewSessionId() {
        return new Identity(subject, name, email, roles, newSid());
    }

    private static String newSid() {
        return UUID.randomUUID().toString();
    }
}
