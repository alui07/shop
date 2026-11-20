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
