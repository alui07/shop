package dev.shop.api.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;

/** Any request that needs the session store gets a 503 Problem Details while Valkey is down, so every operation declares it. */
class ServiceUnavailableResponseCustomizer implements OpenApiCustomizer {

    private static final String STATUS = "503";

    @Override
    @SuppressWarnings("rawtypes")
    public void customise(OpenAPI openApi) {
        Schema problem = new Schema<>().$ref("#/components/schemas/ProblemDetail");
        ApiResponse unavailable = new ApiResponse()
                .description("Service Unavailable")
                .content(new Content().addMediaType("application/problem+json", new MediaType().schema(problem)));
        openApi.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .forEach(operation -> operation.getResponses().addApiResponse(STATUS, unavailable));
    }
}
