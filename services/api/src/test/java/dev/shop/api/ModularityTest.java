package dev.shop.api;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    @Test
    void moduleStructureHasNoViolations() {
        ApplicationModules.of(ApiApplication.class).verify();
    }
}
