package dev.shop.api.payment;

import dev.shop.api.web.Problems;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The refusals the webhook and payment start give. None echoes request input. */
final class WebhookProblem extends ErrorResponseException {

    private WebhookProblem(HttpStatus status, ProblemDetail problem) {
        super(status, problem, null);
    }

    /** One fixed answer whatever check failed. */
    static WebhookProblem badSignature() {
        return of(HttpStatus.UNAUTHORIZED, Problems.WEBHOOK_SIGNATURE, "The signature is not valid");
    }

    static WebhookProblem malformed() {
        return of(HttpStatus.BAD_REQUEST, Problems.VALIDATION, "The event is malformed");
    }

    /** The provider is expected to retry, so the unknown payment is an error, not a success. */
    static WebhookProblem unknownPayment() {
        return of(HttpStatus.NOT_FOUND, Problems.PAYMENT_NOT_FOUND, "No payment matches the event");
    }

    static WebhookProblem tooLarge() {
        return closing(HttpStatus.CONTENT_TOO_LARGE, Problems.BODY_TOO_LARGE, "The request body is too large");
    }

    static WebhookProblem notJson() {
        return closing(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                Problems.UNSUPPORTED_MEDIA_TYPE,
                "The request body must be application/json");
    }

    private static WebhookProblem closing(HttpStatus status, String type, String detail) {
        WebhookProblem problem = of(status, type, detail);
        problem.getHeaders().set(HttpHeaders.CONNECTION, "close");
        return problem;
    }

    private static WebhookProblem of(HttpStatus status, String type, String detail) {
        return new WebhookProblem(status, Problems.problem(status, type, detail, List.of()));
    }
}
