package dev.shop.api.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;

/** Every unsafe operation that needs the session cookie also needs the CSRF header, in the same requirement. */
class CsrfHeaderCustomizer implements OpenApiCustomizer {

    static final String SESSION_COOKIE = "sessionCookie";
    static final String CSRF_HEADER = "csrfHeader";

    private static final List<PathItem.HttpMethod> UNSAFE_METHODS = List.of(
            PathItem.HttpMethod.POST, PathItem.HttpMethod.PUT, PathItem.HttpMethod.PATCH, PathItem.HttpMethod.DELETE);

    @Override
    public void customise(OpenAPI openApi) {
        openApi.getPaths().values().forEach(path -> {
            Map<PathItem.HttpMethod, Operation> operations = path.readOperationsMap();
            UNSAFE_METHODS.stream()
                    .map(operations::get)
                    .filter(operation -> operation != null)
                    .forEach(this::require);
        });
    }

    private void require(Operation operation) {
        List<SecurityRequirement> requirements = operation.getSecurity();
        if (requirements != null) {
            requirements.stream()
                    .filter(requirement -> requirement.containsKey(SESSION_COOKIE))
                    .forEach(requirement -> requirement.addList(CSRF_HEADER));
        }
    }
}
