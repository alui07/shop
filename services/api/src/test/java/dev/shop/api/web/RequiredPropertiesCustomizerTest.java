package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequiredPropertiesCustomizerTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void marksEveryNonNullablePropertyRequiredInSortedOrder() {
        Schema thing = new Schema<>();
        thing.addProperty("zeta", typed("string"));
        thing.addProperty("alpha", typed("integer"));
        thing.addProperty("typeNullable", typed("string", "null"));
        Schema<?> flagNullable = typed("string");
        flagNullable.setNullable(true);
        thing.addProperty("flagNullable", flagNullable);
        thing.addProperty("middle", typed("string"));
        Schema enumeration = typed("string");
        Schema problem = new Schema<>();
        problem.addProperty("detail", typed("string"));
        OpenAPI openApi = new OpenAPI()
                .components(new Components()
                        .addSchemas("Thing", thing)
                        .addSchemas("Colour", enumeration)
                        .addSchemas("ProblemDetail", problem));

        new RequiredPropertiesCustomizer().customise(openApi);

        assertThat(thing.getRequired()).containsExactly("alpha", "middle", "zeta");
        assertThat(enumeration.getRequired()).isNull();
        assertThat(problem.getRequired()).isNull();
    }

    private static Schema<?> typed(String... types) {
        Schema<?> schema = new Schema<>();
        schema.setTypes(new LinkedHashSet<>(List.of(types)));
        return schema;
    }
}
