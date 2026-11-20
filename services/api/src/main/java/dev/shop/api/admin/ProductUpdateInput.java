package dev.shop.api.admin;

import dev.shop.api.web.PlainText;
import dev.shop.api.web.PlainTextRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The slug is not here: it is fixed at creation. {@code version} is the one the client last read. */
record ProductUpdateInput(
        @NotBlank @Size(max = 120) @PlainText @Schema(pattern = PlainTextRules.SINGLE_LINE_PATTERN)
        String name,

        @NotBlank
        @Size(max = 2000)
        @PlainText(allowNewline = true)
        @Schema(
                pattern = PlainTextRules.MULTI_LINE_PATTERN,
                description = "Plain text; line feeds are allowed, tabs and carriage returns are not.")
        String description,

        @NotNull @Min(0) @Max(Rules.MAX_PRICE_MINOR) Long priceMinor,
        @NotNull @Size(max = 100) String categorySlug,
        @NotNull Boolean active,
        @NotNull @Min(0) Long version) {}
