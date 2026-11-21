package dev.shop.api.fakepsp;

import jakarta.validation.constraints.NotNull;

record FakePspCompletion(@NotNull FakePspOutcome outcome) {}
