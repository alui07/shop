package dev.shop.api.admin;

import dev.shop.api.customer.CurrentUser;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "sessionCookie")
class AdminProductController {

    // Written with \x escapes because the pattern is published in the OpenAPI document (ECMA-262 syntax).
    private static final String NO_CONTROL_CHARACTERS = "^[^\\x00-\\x1F\\x7F]*$";
    private static final String PRODUCT_LOCATION = "/api/admin/products/";

    private final AdminProductService service;

    AdminProductController(AdminProductService service) {
        this.service = service;
    }

    @GetMapping("/products")
    ResponseEntity<AdminProductPage> listAdminProducts(
            @RequestParam(required = false) @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return noStore(service.list(q, page, size));
    }

    @GetMapping("/products/{id}")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AdminProduct.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Not Found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<AdminProduct> getAdminProduct(@PathVariable @Min(1) long id) {
        return noStore(service.get(id));
    }

    @PostMapping("/products")
    @ApiResponse(
            responseCode = "201",
            description = "Created",
            headers = @Header(name = "Location", description = "Relative URL of the new product"),
            content = @Content(schema = @Schema(implementation = AdminProduct.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Conflict: the slug is already taken",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<AdminProduct> createAdminProduct(
            @Valid @RequestBody ProductInput input, Authentication authentication) {
        AdminProduct created = service.create(CurrentUser.of(authentication), input);
        return ResponseEntity.created(URI.create(PRODUCT_LOCATION + created.id()))
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    @PutMapping("/products/{id}")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AdminProduct.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Not Found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Conflict: the version is stale",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<AdminProduct> updateAdminProduct(
            @PathVariable @Min(1) long id,
            @Valid @RequestBody ProductUpdateInput input,
            Authentication authentication) {
        return noStore(service.update(CurrentUser.of(authentication), id, input));
    }

    @PutMapping("/products/{id}/stock")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AdminProduct.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Not Found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Conflict: the version is stale",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<AdminProduct> setAdminProductStock(
            @PathVariable @Min(1) long id, @Valid @RequestBody StockInput input, Authentication authentication) {
        return noStore(service.setStock(CurrentUser.of(authentication), id, input));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
