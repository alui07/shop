package dev.shop.api.inventory;

import dev.shop.api.web.Problems;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

class StockBelowReservedException extends ErrorResponseException {

    StockBelowReservedException() {
        super(
                HttpStatus.BAD_REQUEST,
                Problems.validation(
                        "The request is not valid",
                        List.of(new Problems.FieldProblem("onHand", "must not be below the reserved quantity"))),
                null);
    }
}
