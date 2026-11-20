package dev.shop.api.web;

import dev.shop.api.web.Problems.FieldProblem;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String LOCK_RETRY_AFTER_SECONDS = "3";

    @ExceptionHandler(InvalidParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail handleUndecodableParameter(InvalidParameterException exception) {
        LOGGER.debug("Rejected a request parameter that could not be decoded", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "A request parameter is not valid UTF-8");
    }

    /** Method security throws inside the controller, where the catch-all below would turn a denial into a 500. */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ProblemDetail handleAccessDenied(AccessDeniedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access is denied");
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ProblemDetail handleAuthenticationRequired(AuthenticationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = exception.getBindingResult().getAllErrors().stream()
                .map(ApiExceptionHandler::toFieldProblem)
                .toList();
        return handleExceptionInternal(
                exception, Problems.validation("The request is not valid", errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldProblem> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result instanceof ParameterErrors body
                        ? body.getFieldErrors().stream().map(ApiExceptionHandler::toFieldProblem)
                        : result.getResolvableErrors().stream()
                                .map(error -> new FieldProblem(
                                        String.valueOf(
                                                result.getMethodParameter().getParameterName()),
                                        error.getDefaultMessage())))
                .toList();
        return handleExceptionInternal(
                exception, Problems.validation("The request is not valid", errors), headers, status, request);
    }

    /**
     * A body that ran past the size limit while being read is a 413; a JSON value of the wrong type names its
     * property; anything else unreadable keeps Spring's answer.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (causedByOversizedBody(exception)) {
            return handleExceptionInternal(
                    exception, RequestBodyLimitFilter.tooLarge(), headers, HttpStatus.CONTENT_TOO_LARGE, request);
        }
        if (exception.getCause() instanceof MismatchedInputException mismatch
                && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().stream()
                    .map(JacksonException.Reference::getPropertyName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            ProblemDetail problem = Problems.validation(
                    "The request is not valid", List.of(new FieldProblem(field, "has the wrong type")));
            return handleExceptionInternal(exception, problem, headers, status, request);
        }
        return super.handleHttpMessageNotReadable(exception, headers, status, request);
    }

    /**
     * A lock wait that timed out says nothing about the version, so it is not a conflict: the caller may retry. Spring
     * translates only some drivers' lock failures, so PostgreSQL's lock_not_available is recognised by SQLState too.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException exception) {
        if (LockFailures.isLockTimeout(exception)) {
            LOGGER.warn(
                    "A row lock could not be taken in time: {}",
                    exception.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, LOCK_RETRY_AFTER_SECONDS)
                    .body(ProblemDetail.forStatusAndDetail(
                            HttpStatus.SERVICE_UNAVAILABLE, "The product is busy; try again shortly"));
        }
        SQLException sql = LockFailures.firstSqlException(exception);
        if (sql != null) {
            // The database message can carry the offending row, which holds product text.
            LOGGER.error(
                    "Unhandled data access failure: {}, SQLState {}",
                    exception.getClass().getName(),
                    sql.getSQLState());
        } else {
            LOGGER.error(
                    "Unhandled data access failure: {}", exception.getClass().getName());
        }
        return ResponseEntity.internalServerError().body(unexpectedProblem());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ProblemDetail handleUnexpected(Exception exception) {
        LOGGER.error("Unhandled exception", exception);
        return unexpectedProblem();
    }

    private static boolean causedByOversizedBody(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof BodyTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private static ProblemDetail unexpectedProblem() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private static FieldProblem toFieldProblem(ObjectError error) {
        String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
        return new FieldProblem(field, error.getDefaultMessage());
    }
}
