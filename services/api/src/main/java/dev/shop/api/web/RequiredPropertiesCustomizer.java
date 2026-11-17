package dev.shop.api.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;

/** Generated clients should see no optional fields, so every non-nullable property is required. */
class RequiredPropertiesCustomizer implements OpenApiCustomizer {

    /** RFC 9457 members are optional by definition. */
    private static final Set<String> SCHEMAS_WITH_OPTIONAL_PROPERTIES = Set.of("ProblemDetail");

    @Override
    @SuppressWarnings("rawtypes")
    public void customise(OpenAPI openApi) {
        openApi.getComponents().getSchemas().entrySet().stream()
                .filter(entry -> !SCHEMAS_WITH_OPTIONAL_PROPERTIES.contains(entry.getKey()))
                .forEach(entry -> markRequired(entry.getValue()));
    }

    @SuppressWarnings("rawtypes")
    private static void markRequired(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        List<String> required = properties.entrySet().stream()
                .filter(property -> !isNullable(property.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        schema.setRequired(required);
    }

    private static boolean isNullable(Schema<?> schema) {
        boolean nullableType = schema.getTypes() != null && schema.getTypes().contains("null");
        return nullableType || Boolean.TRUE.equals(schema.getNullable());
    }
}
