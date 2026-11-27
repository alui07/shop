package dev.shop.api.idempotency;

import dev.shop.api.web.Problems;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The refusals an idempotency key gives. None echoes request input. */
final class IdempotencyProblem extends ErrorResponseException {

    private final HttpHeaders headers = new HttpHeaders();

    private IdempotencyProblem(HttpStatus status, ProblemDetail problem) {
        super(status, problem, null);
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    static IdempotencyProblem inProgress() {
        IdempotencyProblem problem = new IdempotencyProblem(
                HttpStatus.CONFLICT,
                Problems.problem(
                        HttpStatus.CONFLICT,
                        Problems.IDEMPOTENCY_IN_PROGRESS,
                        "A request with this key is still being processed",
                        List.of()));
        problem.headers.set(HttpHeaders.RETRY_AFTER, "1");
        return problem;
    }

    static IdempotencyProblem keyReused() {
        return new IdempotencyProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                Problems.problem(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        Problems.IDEMPOTENCY_KEY_REUSED,
                        "The key was used with a different request",
                        List.of()));
    }
}
