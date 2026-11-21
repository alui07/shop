package dev.shop.api.fakepsp;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "fake")
@RequestMapping(path = "/api/fake-psp/payments", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "sessionCookie")
class FakePspController {

    private final FakePspService service;

    FakePspController(FakePspService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = FakePspPayment.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No payment has this token (payment-not-found)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<FakePspPayment> read(@PathVariable String token) {
        return noStore(service.read(token));
    }

    @PostMapping("/{token}/complete")
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = FakePspPayment.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No payment has this token (payment-not-found)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Already completed, or the session has expired (payment-state)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<FakePspPayment> complete(
            @PathVariable String token, @Valid @RequestBody FakePspCompletion completion) {
        return noStore(service.complete(token, completion.outcome()));
    }

    private static ResponseEntity<FakePspPayment> noStore(FakePspPayment payment) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payment);
    }
}
