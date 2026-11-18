package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.shop.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Defence in depth: besides the URL rule, every admin handler must carry its own method-security check. */
@IntegrationTest
class AdminEndpointsAreProtectedIT {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyAdminHandlerRequiresTheAdminRole() {
        var adminHandlers = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPathPatternsCondition().getPatterns().stream()
                        .anyMatch(pattern -> pattern.getPatternString().startsWith("/api/admin")))
                .map(entry -> entry.getValue())
                .toList();

        assertThat(adminHandlers).isNotEmpty();
        assertThat(adminHandlers)
                .allSatisfy(handler ->
                        assertThat(isAdminOnly(handler)).as(handler.toString()).isTrue());
    }

    private static boolean isAdminOnly(HandlerMethod handler) {
        PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        }
        return annotation != null && annotation.value().equals("hasRole('ADMIN')");
    }
}
