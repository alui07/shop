package dev.shop.api;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/** The named interfaces really are the only way in: modules that may not reach something are caught. */
class ModuleRulesTest {

    /** Everything the normal verification sees, plus the one test class that breaks the rule on purpose. */
    private static ImportOption mainClassesAnd(String violation) {
        return location -> !location.contains("/test-classes/") || location.contains(violation);
    }

    private static final String ORDER_REPOSITORY = "dev.shop.api.order.OrderRepository";

    private static final ArchRule LOCK_BY_ID_ONLY_FROM_ORDER_PAYMENTS = noClasses()
            .that()
            .doNotHaveFullyQualifiedName("dev.shop.api.order.OrderPayments")
            .should()
            .callMethodWhere(target(name("lockById")).and(target(owner(name(ORDER_REPOSITORY)))));

    @Test
    void theAdminModuleCannotReachStockReservations() {
        ApplicationModules modules = ApplicationModules.of(ApiApplication.class, mainClassesAnd("ReservationPeeker"));

        assertThatThrownBy(modules::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("Module 'admin' depends on named interface(s) 'inventory :: reservations'")
                .hasMessageContaining("ReservationPeeker");
    }

    @Test
    void theNotificationModuleCannotReachOrderPayments() {
        ApplicationModules modules = ApplicationModules.of(ApiApplication.class, mainClassesAnd("OrderPaymentsPeeker"));

        assertThatThrownBy(modules::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("Module 'notification' depends on named interface(s) 'order :: payments'")
                .hasMessageContaining("OrderPaymentsPeeker");
    }

    @Test
    void theUnscopedOrderLockIsCalledOnlyFromOrderPayments() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.shop.api");

        LOCK_BY_ID_ONLY_FROM_ORDER_PAYMENTS.check(production);
    }

    @Test
    void anyOtherCallerOfTheUnscopedOrderLockIsReported() {
        JavaClasses withThePeeker = new ClassFileImporter()
                .withImportOption(mainClassesAnd("LockByIdPeeker"))
                .importPackages("dev.shop.api");

        assertThatThrownBy(() -> LOCK_BY_ID_ONLY_FROM_ORDER_PAYMENTS.check(withThePeeker))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("LockByIdPeeker");
    }
}
