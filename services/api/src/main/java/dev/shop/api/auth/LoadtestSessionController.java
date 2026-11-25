package dev.shop.api.auth;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Mints a customer session the way a login would leave it, minus Keycloak (no authorized client is stored). */
@RestController
@Profile("loadtest")
@Hidden
class LoadtestSessionController {

    static final String TOKEN_HEADER = "Shop-Loadtest-Token";

    private static final Logger LOGGER = LoggerFactory.getLogger(LoadtestSessionController.class);

    private final byte[] tokenDigest;
    private final LoadtestProperties properties;
    private final MintLimiter limiter;
    private final Clock clock;

    LoadtestSessionController(LoadtestProperties properties, MintLimiter limiter, Clock clock) {
        this.properties = properties;
        this.tokenDigest = sha256(properties.token());
        this.limiter = limiter;
        this.clock = clock;
    }

    @PostMapping(LoadtestSessionConfiguration.MINT_PATH)
    ResponseEntity<?> mint(
            HttpServletRequest request, @RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        if (token == null || !MessageDigest.isEqual(tokenDigest, sha256(token))) {
            LOGGER.warn("Rejected a loadtest session request: missing or wrong token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required"));
        }
        if (!limiter.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ProblemDetail.forStatusAndDetail(
                            HttpStatus.TOO_MANY_REQUESTS, "Too many sessions requested"));
        }
        String subject = "loadtest-" + UUID.randomUUID();
        startSession(request, subject);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("subject", subject));
    }

    private void startSession(HttpServletRequest request, String subject) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        Instant now = clock.instant();
        OidcIdToken idToken = new OidcIdToken(
                "loadtest",
                now,
                now.plus(properties.sessionTimeout()),
                Map.of(
                        "sub",
                        subject,
                        "roles",
                        List.of("customer"),
                        "name",
                        subject,
                        "email",
                        subject + "@loadtest.invalid",
                        "email_verified",
                        true));
        var authorities = ShopAuthorities.from(idToken);
        var authentication = new OAuth2AuthenticationToken(
                new DefaultOidcUser(authorities, idToken, "sub"),
                authorities,
                KeycloakClientRegistration.REGISTRATION_ID);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        HttpSession session = request.getSession(true);
        session.setAttribute(SessionAttributes.AUTHENTICATED_AT, now);
        session.setAttribute(SessionAttributes.LAST_REVALIDATION_ATTEMPT, now);
        session.setAttribute(SessionAttributes.LAST_REVALIDATION_SUCCESS, now);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setMaxInactiveInterval((int) properties.sessionTimeout().toSeconds());
    }

    private static byte[] sha256(String value) {
        return digest(value, "SHA-256");
    }

    static byte[] digest(String value, String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unknown) {
            throw new IllegalArgumentException("Unknown digest algorithm", unknown);
        }
    }
}
