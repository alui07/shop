package dev.shop.api.inventory;

import dev.shop.api.web.Problems;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

class StockVersionConflictException extends ErrorResponseException {

    StockVersionConflictException() {
        super(
                HttpStatus.CONFLICT,
                Problems.problem(
                        HttpStatus.CONFLICT,
                        Problems.VERSION_CONFLICT,
                        "The stock was changed by someone else",
                        List.of()),
                null);
    }
}
