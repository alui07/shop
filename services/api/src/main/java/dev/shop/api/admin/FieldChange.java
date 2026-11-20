package dev.shop.api.admin;

import io.swagger.v3.oas.annotations.media.Schema;

record FieldChange(
        String field,
        @Schema(nullable = true) String from,
        @Schema(nullable = true) String to) {}
