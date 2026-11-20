package dev.shop.api.catalog;

import dev.shop.api.web.Problems;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

class UnknownCategoryException extends ErrorResponseException {

    UnknownCategoryException() {
        super(
                HttpStatus.BAD_REQUEST,
                Problems.validation(
                        "The request is not valid",
                        List.of(new Problems.FieldProblem("categorySlug", "must name an existing category"))),
                null);
    }
}
