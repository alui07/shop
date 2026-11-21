package dev.shop.api.web;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** The Problem Details shapes the admin API promises: a fixed {@code type} and an {@code errors} array. */
public final class Problems {

    public static final String VALIDATION = "tag:shop.dev,2026:validation";
    public static final String DUPLICATE_SLUG = "tag:shop.dev,2026:duplicate-slug";
    public static final String VERSION_CONFLICT = "tag:shop.dev,2026:version-conflict";
    public static final String CART_CHANGED = "tag:shop.dev,2026:cart-changed";
    public static final String PRICE_CHANGED = "tag:shop.dev,2026:price-changed";
    public static final String OUT_OF_STOCK = "tag:shop.dev,2026:out-of-stock";
    public static final String CART_FULL = "tag:shop.dev,2026:cart-full";
    public static final String TOO_MANY_PENDING_ORDERS = "tag:shop.dev,2026:too-many-pending-orders";
    public static final String RESERVATION_LIMIT = "tag:shop.dev,2026:reservation-limit";
    public static final String ORDER_STATE = "tag:shop.dev,2026:order-state";
    public static final String IDEMPOTENCY_IN_PROGRESS = "tag:shop.dev,2026:idempotency-in-progress";
    public static final String IDEMPOTENCY_KEY_REUSED = "tag:shop.dev,2026:idempotency-key-reused";
    public static final String BODY_TOO_LARGE = "tag:shop.dev,2026:body-too-large";
    public static final String PAYMENT_STATE = "tag:shop.dev,2026:payment-state";
    public static final String PAYMENT_NOT_FOUND = "tag:shop.dev,2026:payment-not-found";
    public static final String WEBHOOK_SIGNATURE = "tag:shop.dev,2026:webhook-signature";
    public static final String UNSUPPORTED_MEDIA_TYPE = "tag:shop.dev,2026:unsupported-media-type";

    private Problems() {}

    public record FieldProblem(String field, String message) {}

    public static ProblemDetail problem(HttpStatus status, String type, String detail, List<FieldProblem> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setProperty("errors", errors);
        return problem;
    }

    public static ProblemDetail validation(String detail, List<FieldProblem> errors) {
        return problem(HttpStatus.BAD_REQUEST, VALIDATION, detail, errors);
    }
}
