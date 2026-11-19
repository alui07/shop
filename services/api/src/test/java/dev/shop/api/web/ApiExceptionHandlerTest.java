package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void aMissingAuthenticationInsideAControllerIsA401Problem() {
        ProblemDetail problem =
                handler.handleAuthenticationRequired(new AuthenticationCredentialsNotFoundException("none"));

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getDetail()).isEqualTo("Authentication required");
    }

    @Test
    void aLockTimeoutIsA503WithRetryAfterWhetherSpringOrOnlyTheSqlStateSaysSo() {
        ResponseEntity<ProblemDetail> byType = handler.handleDataAccess(new CannotAcquireLockException("busy"));
        ResponseEntity<ProblemDetail> bySqlState = handler.handleDataAccess(
                new UncategorizedSQLException("update", "UPDATE x", new SQLException("busy", "55P03")));

        for (ResponseEntity<ProblemDetail> response : List.of(byType, bySqlState)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3");
        }
    }

    @Test
    void theSqlStateIsFoundAnywhereInTheCauseChain() {
        ResponseEntity<ProblemDetail> response = handler.handleDataAccess(
                new UncategorizedSQLException("update", "UPDATE x", new SQLException("busy", "55P03")) {
                    @Override
                    public synchronized Throwable getCause() {
                        return new RuntimeException("wrapper", new SQLException("busy", "55P03"));
                    }
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void databaseFailuresAreLoggedByClassAndSqlStateNeverByMessage(CapturedOutput output) {
        handler.handleDataAccess(new DataIntegrityViolationException(
                "Failing row contains (secret text)", new SQLException("Failing row contains (secret text)", "23505")));
        handler.handleDataAccess(new DataRetrievalFailureException("secret text again"));

        assertThat(output.getAll()).contains("23505").doesNotContain("secret text");
    }

    @Test
    void otherDatabaseFailuresAreAGeneric500() {
        ResponseEntity<ProblemDetail> withSqlState = handler.handleDataAccess(new DataIntegrityViolationException(
                "Failing row contains (secret text)", new SQLException("x", "23505")));
        ResponseEntity<ProblemDetail> withoutSqlException =
                handler.handleDataAccess(new DataRetrievalFailureException("gone"));

        for (ResponseEntity<ProblemDetail> response : List.of(withSqlState, withoutSqlException)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred");
        }
    }

    @Test
    void anyOtherFailureIsA500Problem() {
        assertThat(handler.handleUnexpected(new IllegalStateException("boom")).getStatus())
                .isEqualTo(500);
    }

    @Test
    void aBodyValidationErrorNamesTheFieldAndAGlobalErrorNamesTheObject() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "productInput");
        result.addError(new FieldError("productInput", "name", "must not be blank"));
        result.addError(new ObjectError("productInput", "is inconsistent"));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(Object.class.getMethod("toString"), -1), result);

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(
                exception,
                new HttpHeaders(),
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(new MockHttpServletRequest()));

        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem.getProperties().get("errors"))
                .isEqualTo(List.of(
                        new Problems.FieldProblem("name", "must not be blank"),
                        new Problems.FieldProblem("productInput", "is inconsistent")));
        assertThat(problem.getType()).hasToString(Problems.VALIDATION);
    }
}
