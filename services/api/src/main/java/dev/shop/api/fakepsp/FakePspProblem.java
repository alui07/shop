package dev.shop.api.fakepsp;

import dev.shop.api.web.Problems;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

final class FakePspProblem extends ErrorResponseException {

    private FakePspProblem(HttpStatus status, String type, String detail) {
        super(status, Problems.problem(status, type, detail, List.of()), null);
    }

    static FakePspProblem notFound() {
        return new FakePspProblem(HttpStatus.NOT_FOUND, Problems.PAYMENT_NOT_FOUND, "No payment has this token");
    }

    /** Already completed, or the session ran out. */
    static FakePspProblem state() {
        return new FakePspProblem(
                HttpStatus.CONFLICT, Problems.PAYMENT_STATE, "The payment was completed or the session has expired");
    }
}
