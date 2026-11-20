package dev.shop.api.catalog;

import dev.shop.api.web.Problems;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

class VersionConflictException extends ErrorResponseException {

    VersionConflictException() {
        super(
                HttpStatus.CONFLICT,
                Problems.problem(
                        HttpStatus.CONFLICT,
                        Problems.VERSION_CONFLICT,
                        "The product was changed by someone else",
                        List.of()),
                null);
    }
}
