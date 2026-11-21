package dev.shop.api.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.io.IOException;
import java.security.GeneralSecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping(path = "/api/payments/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
class WebhookController {

    static final String SIGNATURE_HEADER = "Shop-Signature";
    static final int LIMIT_BYTES = 64 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookVerifier verifier;
    private final WebhookProcessor processor;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    WebhookController(
            WebhookVerifier verifier, WebhookProcessor processor, JsonMapper jsonMapper, Validator validator) {
        this.verifier = verifier;
        this.processor = processor;
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    @PostMapping
    @Operation(
            summary = "Receive a payment provider event",
            description = "Authenticated by the HMAC signature only: no session, no CSRF token.",
            parameters =
                    @Parameter(
                            name = "Shop-Signature",
                            in = ParameterIn.HEADER,
                            required = true,
                            description =
                                    "t=<unix seconds>,v1=<64 lowercase hex>; v1 = HMAC-SHA256(secret, t + \".\" + body)"),
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = WebhookEvent.class))))
    @ApiResponse(
            responseCode = "200",
            description = "OK, also for an event id seen before",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WebhookAck.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Malformed JSON or an unknown event type, after the signature was verified",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Signature missing, malformed, stale or wrong (webhook-signature)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No payment matches the event; the provider is expected to retry (payment-not-found)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "413",
            description = "Body over 64 KiB (body-too-large)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "415",
            description = "Content-Type is not application/json (unsupported-media-type)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<WebhookAck> receive(HttpServletRequest request) throws IOException, GeneralSecurityException {
        byte[] body = readBody(request);
        verifier.refusal(request.getHeaders(SIGNATURE_HEADER), body).ifPresent(reason -> {
            LOGGER.warn("Webhook refused: {}", reason);
            throw WebhookProblem.badSignature();
        });
        processor.process(parse(body));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new WebhookAck(true));
    }

    /** Reads at most the limit plus one byte, whatever Content-Length claims. */
    private static byte[] readBody(HttpServletRequest request) throws IOException {
        if (!isJson(request.getContentType())) {
            throw WebhookProblem.notJson();
        }
        if (request.getContentLengthLong() > LIMIT_BYTES) {
            throw WebhookProblem.tooLarge();
        }
        byte[] body = request.getInputStream().readNBytes(LIMIT_BYTES + 1);
        if (body.length > LIMIT_BYTES) {
            throw WebhookProblem.tooLarge();
        }
        return body;
    }

    private static boolean isJson(String contentType) {
        try {
            return contentType != null
                    && MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    private WebhookEvent parse(byte[] body) {
        try {
            WebhookEvent event = jsonMapper.readValue(body, WebhookEvent.class);
            if (event == null || !validator.validate(event).isEmpty()) {
                throw WebhookProblem.malformed();
            }
            return event;
        } catch (JacksonException e) {
            throw WebhookProblem.malformed();
        }
    }
}
