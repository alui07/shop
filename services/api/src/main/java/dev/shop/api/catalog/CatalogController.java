package dev.shop.api.catalog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
class CatalogController {

    // Postgres rejects NUL in text, so control characters must fail validation instead of reaching SQL.
    // Written with \x escapes because the pattern is published in the OpenAPI document (ECMA-262 syntax).
    private static final String NO_CONTROL_CHARACTERS = "^[^\\x00-\\x1F\\x7F]*$";

    private final CatalogService service;

    CatalogController(CatalogService service) {
        this.service = service;
    }

    @InitBinder
    void registerSortEditor(WebDataBinder binder) {
        binder.registerCustomEditor(ProductSort.class, new ProductSortEditor());
    }

    @GetMapping("/categories")
    ResponseEntity<List<Category>> listCategories() {
        return revalidated(service.categories());
    }

    @GetMapping("/products")
    ResponseEntity<ProductPage> listProducts(
            @RequestParam(required = false) @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS) String q,
            @RequestParam(required = false) @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS) String category,
            @RequestParam(required = false) ProductSort sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(48) int size) {
        return revalidated(service.search(q, category, sort, page, size));
    }

    @GetMapping("/products/{slug}")
    ResponseEntity<ProductDetail> getProduct(
            @PathVariable @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS) String slug) {
        return revalidated(service.product(slug));
    }

    private static <T> ResponseEntity<T> revalidated(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(body);
    }
}
