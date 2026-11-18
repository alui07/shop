package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

class ApiExceptionHandlerTest {

    @Test
    void aMissingAuthenticationInsideAControllerIsA401Problem() {
        ProblemDetail problem = new ApiExceptionHandler()
                .handleAuthenticationRequired(new AuthenticationCredentialsNotFoundException("none"));

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getDetail()).isEqualTo("Authentication required");
    }
}
