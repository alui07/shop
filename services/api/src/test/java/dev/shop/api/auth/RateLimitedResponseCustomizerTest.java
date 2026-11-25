package dev.shop.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class RateLimitedResponseCustomizerTest {

    private static Operation operation() {
        return new Operation().responses(new ApiResponses());
    }

    @Test
    void onlyOperationsARuleCoversDeclareTheTooManyRequestsProblem() {
        Operation checkout = operation();
        Operation cartRead = operation();
        Operation cartWrite = operation();
        Operation cartDelete = operation();
        OpenAPI openApi = new OpenAPI()
                .paths(new Paths()
                        .addPathItem("/api/checkout", new PathItem().post(checkout))
                        .addPathItem("/api/cart", new PathItem().get(cartRead).post(cartWrite))
                        .addPathItem("/api/cart/lines/{slug}", new PathItem().delete(cartDelete)));

        new RateLimitedResponseCustomizer().customise(openApi);

        assertThat(checkout.getResponses()
                        .get("429")
                        .getContent()
                        .get("application/problem+json")
                        .getSchema()
                        .get$ref())
                .isEqualTo("#/components/schemas/ProblemDetail");
        assertThat(checkout.getResponses().get("429").getHeaders()).containsKey("Retry-After");
        assertThat(cartWrite.getResponses()).containsKey("429");
        assertThat(cartDelete.getResponses()).containsKey("429");
        assertThat(cartRead.getResponses()).doesNotContainKey("429");
    }
}
