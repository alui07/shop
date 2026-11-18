package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class ServiceUnavailableResponseCustomizerTest {

    @Test
    void everyOperationDeclaresTheProblemDetailsServiceUnavailable() {
        Operation get = new Operation().responses(new ApiResponses());
        Operation post = new Operation().responses(new ApiResponses());
        OpenAPI openApi = new OpenAPI()
                .paths(new Paths().addPathItem("/x", new PathItem().get(get).post(post)));

        new ServiceUnavailableResponseCustomizer().customise(openApi);

        assertThat(get.getResponses()
                        .get("503")
                        .getContent()
                        .get("application/problem+json")
                        .getSchema()
                        .get$ref())
                .isEqualTo("#/components/schemas/ProblemDetail");
        assertThat(post.getResponses()).containsKey("503");
    }
}
