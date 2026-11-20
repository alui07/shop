package dev.shop.api.order;

import dev.shop.api.customer.CurrentUser;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/checkout", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "sessionCookie")
class CheckoutController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENCY_REPLAYED = "Idempotency-Replayed";

    private final CheckoutService service;

    CheckoutController(CheckoutService service) {
        this.service = service;
    }

    @PostMapping
    @ApiResponse(
            responseCode = "201",
            description =
                    "Created. A repeated key answers 201 again, with the order's current state and Idempotency-Replayed: true",
            headers = {
                @Header(name = "Location", description = "Relative URL of the order"),
                @Header(name = IDEMPOTENCY_REPLAYED, description = "true when the key had been used before")
            },
            content = @Content(schema = @Schema(implementation = Order.class)))
    @ApiResponse(
            responseCode = "409",
            description =
                    "Conflict: cart-changed, price-changed, out-of-stock, too-many-pending-orders, reservation-limit"
                            + " or idempotency-in-progress (with Retry-After)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "413",
            description = "Request body over 16 KiB (body-too-large)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Unprocessable: the key was used with a different request (idempotency-key-reused)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "415",
            description = "Request body is not JSON (unsupported-media-type)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<Order> checkout(
            @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody CheckoutInput input,
            Authentication authentication) {
        CheckoutResult result = service.checkout(CurrentUser.of(authentication), idempotencyKey, input);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                        URI.create("/api/orders/" + result.order().id()))
                .cacheControl(CacheControl.noStore());
        if (result.replayed()) {
            response.header(IDEMPOTENCY_REPLAYED, "true");
        }
        return response.body(result.order());
    }
}
