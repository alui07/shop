package dev.shop.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/** The named interfaces really are the only way in: a module that is not allowed to reach reservations is caught. */
class ModuleRulesTest {

    /** Everything the normal verification sees, plus the one test class that breaks the rule on purpose. */
    private static final ImportOption MAIN_CLASSES_AND_THE_VIOLATION =
            location -> !location.contains("/test-classes/") || location.contains("ReservationPeeker");

    @Test
    void theAdminModuleCannotReachStockReservations() {
        ApplicationModules modules = ApplicationModules.of(ApiApplication.class, MAIN_CLASSES_AND_THE_VIOLATION);

        assertThatThrownBy(modules::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("Module 'admin' depends on named interface(s) 'inventory :: reservations'")
                .hasMessageContaining("ReservationPeeker");
    }
}
