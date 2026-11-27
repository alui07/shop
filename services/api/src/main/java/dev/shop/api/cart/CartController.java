package dev.shop.api.cart;

import dev.shop.api.customer.CurrentUser;
import dev.shop.api.idempotency.IdempotencyKeys;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/cart", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "sessionCookie")
class CartController {

    // Written with \x escapes because the pattern is published in the OpenAPI document (ECMA-262 syntax).
    private static final String NO_CONTROL_CHARACTERS = "^[^\\x00-\\x1F\\x7F]*$";

    private final CartService service;

    CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<Cart> getCart(Authentication authentication) {
        return noStore(service.get(CurrentUser.of(authentication).subject()));
    }

    @PutMapping("/lines/{slug}")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Cart.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Not Found: unknown or inactive product",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Conflict: the cart already has 20 lines (cart-full)",
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
            responseCode = "415",
            description = "Request body is not JSON (unsupported-media-type)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<Cart> putCartLine(
            @PathVariable @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS) String slug,
            @Valid @RequestBody CartLineInput input,
            Authentication authentication) {
        return noStore(service.putLine(CurrentUser.of(authentication).subject(), slug, input.quantity()));
    }

    @PostMapping("/lines/{slug}/additions")
    @ApiResponse(
            responseCode = "200",
            description =
                    "OK: the quantity was added, creating the line if needed. A repeated key answers 200 again, with the"
                            + " cart's current state and Idempotency-Replayed: true",
            headers =
                    @Header(
                            name = IdempotencyKeys.REPLAYED_HEADER,
                            description = "true when the key had been used before"),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Cart.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Bad Request: invalid quantity or slug, or a missing or malformed Idempotency-Key header",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Not Found: unknown or inactive product",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Conflict: line-quantity-limit (the line would hold more than 10; the problem also carries"
                    + " quantityInCart and maxQuantity), cart-full (the cart already has 20 lines) or"
                    + " idempotency-in-progress (with Retry-After)",
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
            responseCode = "415",
            description = "Request body is not JSON (unsupported-media-type)",
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
    ResponseEntity<Cart> addToCartLine(
            @PathVariable @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS) String slug,
            @RequestHeader(IdempotencyKeys.HEADER) UUID idempotencyKey,
            @Valid @RequestBody CartLineInput input,
            Authentication authentication) {
        CartAddition addition =
                service.addToLine(CurrentUser.of(authentication).subject(), idempotencyKey, slug, input.quantity());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (addition.replayed()) {
            response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
        }
        return response.body(addition.cart());
    }

    @DeleteMapping("/lines/{slug}")
    ResponseEntity<Cart> deleteCartLine(
            @PathVariable @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS) String slug,
            Authentication authentication) {
        return noStore(service.removeLine(CurrentUser.of(authentication).subject(), slug));
    }

    private static ResponseEntity<Cart> noStore(Cart cart) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cart);
    }
}
