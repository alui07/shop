package dev.shop.api.admin;

import dev.shop.api.catalog.AdminProductPage;
import dev.shop.api.catalog.ProductAdministration;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    private final ProductAdministration products;

    AdminProductController(ProductAdministration products) {
        this.products = products;
    }

    @GetMapping("/products")
    ResponseEntity<AdminProductPage> listAdminProducts(
            @RequestParam(required = false) @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(products.list(q, page, size));
    }
}
