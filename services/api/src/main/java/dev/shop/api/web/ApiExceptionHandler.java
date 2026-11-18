package dev.shop.api.web;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ProblemDetail handleUnexpected(Exception exception) {
        LOGGER.error("Unhandled exception", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
