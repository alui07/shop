package dev.shop.api.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for Keycloak that serves the JWK set and the token endpoint and signs ID and logout tokens with any
 * claims, so tests can forge the bad tokens a real Keycloak never issues.
 */
public final class StubIdentityProvider implements AutoCloseable {

    public static final String ISSUER = "https://idp.example.test/realms/shop";
    public static final String CLIENT_ID = "shop-api";
    public static final String CLIENT_SECRET = "test-client-secret-test-client-secret";
    public static final String BACKCHANNEL_EVENT = "http://schemas.openid.net/event/backchannel-logout";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REALM_PATH = "/realms/shop/protocol/openid-connect";

    /** What the refresh-token grant does. */
    public enum RefreshMode {
        OK,
        INVALID_GRANT,
        SERVER_ERROR,
        NO_ID_TOKEN,
        ID_TOKEN_FOR_ANOTHER_USER,
        FORGED_ID_TOKEN
    }

    /** Everything the token endpoint needs to answer an authorization-code exchange. */
    public record Login(
            Identity identity,
            String nonce,
            String codeChallenge,
            Consumer<JWTClaimsSet.Builder> tweak,
            Signing signing) {

        public Login(Identity identity, Consumer<JWTClaimsSet.Builder> tweak, Signing signing) {
            this(identity, null, null, tweak, signing);
        }
    }

    public enum Signing {
        VALID,
        UNKNOWN_KEY,
        UNSIGNED
    }

    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey otherKey;
    private final Map<String, Login> codes = new ConcurrentHashMap<>();
    private final Map<String, Identity> refreshTokens = new ConcurrentHashMap<>();
    private final Map<String, Identity> currentIdentities = new ConcurrentHashMap<>();
    private final AtomicInteger refreshCount = new AtomicInteger();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private volatile RefreshMode refreshMode = RefreshMode.OK;
    private volatile long refreshDelayMillis;
    private volatile String lastCodeVerifier;

    public StubIdentityProvider() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("stub-key").generate();
            otherKey = new RSAKeyGenerator(2048).keyID("unknown-key").generate();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException("Could not start the stub identity provider", e);
        }
        server.createContext(REALM_PATH + "/certs", this::serveKeys);
        server.createContext(REALM_PATH + "/token", this::serveToken);
        server.start();
    }

    public String internalRealmUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/realms/shop";
    }

    public String issueCode(Login login) {
        String code = UUID.randomUUID().toString();
        codes.put(code, login);
        currentIdentities.put(login.identity().subject(), login.identity());
        return code;
    }

    public void setRefreshMode(RefreshMode mode) {
        this.refreshMode = mode;
    }

    public void setRefreshDelayMillis(long millis) {
        this.refreshDelayMillis = millis;
    }

    /** Changes what the next refresh-token grant says about the user, like an admin editing roles in Keycloak. */
    public void updateIdentity(Identity identity) {
        currentIdentities.put(identity.subject(), identity);
    }

    public int refreshCount() {
        return refreshCount.get();
    }

    public int tokenRequests() {
        return tokenRequests.get();
    }

    public String lastCodeVerifier() {
        return lastCodeVerifier;
    }

    public void reset() {
        refreshMode = RefreshMode.OK;
        refreshDelayMillis = 0;
        refreshCount.set(0);
        tokenRequests.set(0);
    }

    public String idToken(Identity identity, String nonce, Consumer<JWTClaimsSet.Builder> tweak) {
        return sign(idTokenClaims(identity, nonce, tweak), signingKey);
    }

    public String logoutToken(Consumer<JWTClaimsSet.Builder> tweak) {
        return logoutToken(Signing.VALID, tweak);
    }

    public String logoutToken(Signing signing, Consumer<JWTClaimsSet.Builder> tweak) {
        JWTClaimsSet claims = logoutClaims(tweak);
        return switch (signing) {
            case VALID -> sign(claims, signingKey);
            case UNKNOWN_KEY -> sign(claims, otherKey);
            case UNSIGNED -> new PlainJWT(claims).serialize();
        };
    }

    private JWTClaimsSet idTokenClaims(Identity identity, String nonce, Consumer<JWTClaimsSet.Builder> tweak) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(identity.subject())
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("azp", CLIENT_ID)
                .claim("sid", identity.sessionId())
                .claim("name", identity.name())
                .claim("email", identity.email())
                .claim("preferred_username", identity.email())
                .claim("roles", identity.roles());
        if (nonce != null) {
            claims.claim("nonce", nonce);
        }
        tweak.accept(claims);
        return claims.build();
    }

    private JWTClaimsSet logoutClaims(Consumer<JWTClaimsSet.Builder> tweak) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .issueTime(new Date())
                .jwtID(UUID.randomUUID().toString())
                .claim("events", Map.of(BACKCHANNEL_EVENT, Map.of()));
        tweak.accept(claims);
        return claims.build();
    }

    private static String sign(JWTClaimsSet claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveKeys(HttpExchange exchange) throws IOException {
        respond(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toString());
    }

    private void serveToken(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        Map<String, String> form =
                parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if ("refresh_token".equals(form.get("grant_type"))) {
            serveRefresh(exchange, form);
        } else {
            serveCodeExchange(exchange, form);
        }
    }

    private void serveCodeExchange(HttpExchange exchange, Map<String, String> form) throws IOException {
        Login login = codes.remove(form.get("code"));
        lastCodeVerifier = form.get("code_verifier");
        if (login == null || !challengeMatches(login.codeChallenge(), lastCodeVerifier)) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        JWTClaimsSet claims = idTokenClaims(login.identity(), login.nonce(), login.tweak());
        String idToken = switch (login.signing()) {
            case VALID -> sign(claims, signingKey);
            case UNKNOWN_KEY -> sign(claims, otherKey);
            case UNSIGNED -> new PlainJWT(claims).serialize();
        };
        respondWithTokens(exchange, login.identity(), idToken);
    }

    private void serveRefresh(HttpExchange exchange, Map<String, String> form) throws IOException {
        refreshCount.incrementAndGet();
        sleep(refreshDelayMillis);
        if (refreshMode == RefreshMode.SERVER_ERROR) {
            respond(exchange, 503, "Service Unavailable");
            return;
        }
        Identity known = refreshTokens.remove(form.get("refresh_token"));
        if (refreshMode == RefreshMode.INVALID_GRANT || known == null) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Session not active\"}");
        } else {
            Identity current = currentIdentities.getOrDefault(known.subject(), known);
            respondWithTokens(exchange, current, refreshIdToken(current));
        }
    }

    private String refreshIdToken(Identity current) {
        return switch (refreshMode) {
            case NO_ID_TOKEN -> null;
            case ID_TOKEN_FOR_ANOTHER_USER -> idToken(current.withSubject("someone-else"), null, claims -> {});
            case FORGED_ID_TOKEN ->
                idToken(current, null, claims -> claims.issuer("https://evil.example.test/realms/shop"));
            default -> idToken(current, null, claims -> {});
        };
    }

    private void respondWithTokens(HttpExchange exchange, Identity identity, String idToken) throws IOException {
        String refreshToken = UUID.randomUUID().toString();
        refreshTokens.put(refreshToken, identity);
        Map<String, Object> body = new HashMap<>();
        body.put("access_token", UUID.randomUUID().toString());
        body.put("token_type", "Bearer");
        body.put("expires_in", 300);
        body.put("refresh_token", refreshToken);
        if (idToken != null) {
            body.put("id_token", idToken);
        }
        body.put("scope", "openid profile email");
        respond(exchange, 200, JSON.writeValueAsString(body));
    }

    private static boolean challengeMatches(String challenge, String verifier) {
        if (challenge == null) {
            return true;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return challenge.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                form.put(
                        URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
