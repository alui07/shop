package dev.shop.api.auth;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;

/** Every operation a rate limit rule covers can answer 429, so the contract says so. */
final class RateLimitedResponseCustomizer implements OpenApiCustomizer {

    private static final String STATUS = "429";

    @Override
    @SuppressWarnings("rawtypes")
    public void customise(OpenAPI openApi) {
        Schema problem = new Schema<>().$ref("#/components/schemas/ProblemDetail");
        ApiResponse tooManyRequests = new ApiResponse()
                .description("Too Many Requests")
                .addHeaderObject(
                        "Retry-After",
                        new Header().description("Seconds to wait").schema(new IntegerSchema()))
                .content(new Content().addMediaType("application/problem+json", new MediaType().schema(problem)));
        openApi.getPaths().forEach((path, item) -> {
            for (Map.Entry<PathItem.HttpMethod, io.swagger.v3.oas.models.Operation> operation :
                    item.readOperationsMap().entrySet()) {
                if (isLimited(operation.getKey().name(), path)) {
                    operation.getValue().getResponses().addApiResponse(STATUS, tooManyRequests);
                }
            }
        });
    }

    private static boolean isLimited(String method, String path) {
        for (RateLimitRule rule : RateLimitRule.values()) {
            if (rule.appliesTo(method, path)) {
                return true;
            }
        }
        return false;
    }
}
