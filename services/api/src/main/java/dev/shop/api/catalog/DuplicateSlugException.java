package dev.shop.api.catalog;

import dev.shop.api.web.Problems;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

class DuplicateSlugException extends ErrorResponseException {

    DuplicateSlugException() {
        super(
                HttpStatus.CONFLICT,
                Problems.problem(
                        HttpStatus.CONFLICT,
                        Problems.DUPLICATE_SLUG,
                        "A product with this slug already exists",
                        List.of(new Problems.FieldProblem("slug", "is already taken"))),
                null);
    }
}
