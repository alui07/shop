package dev.shop.api.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/** Shared wiring for integration tests that sign in through the stub identity provider. */
@IntegrationTest
public abstract class AuthIT {

    public static final String PUBLIC_ORIGIN = "https://shop.example.test";

    @LocalServerPort
    protected int port;

    @Autowired
    protected StubIdentityProvider idp;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected SessionRepository<?> sessions;

    @AfterEach
    void resetStubAndClock() {
        idp.reset();
        clock.reset();
    }

    protected TestBrowser browser() {
        return new TestBrowser(port, idp);
    }

    protected TestBrowser signedInAs(Identity identity) {
        TestBrowser browser = browser();
        browser.login(identity);
        return browser;
    }

    /** Spring Session sends the session id base64 encoded. */
    protected static String sessionIdOf(TestBrowser browser) {
        String cookie = browser.cookie(TestBrowser.SESSION_COOKIE);
        return cookie == null ? null : new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
    }

    /** Stores a session directly in Valkey and returns a browser that carries its cookie. */
    protected TestBrowser browserWithSession(Consumer<Session> initialise) {
        String sessionId = saveSession(sessions, initialise);
        TestBrowser browser = browser();
        browser.setCookie(
                TestBrowser.SESSION_COOKIE,
                Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
        return browser;
    }

    protected static Authentication adminPrincipal() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "crafted", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static <S extends Session> String saveSession(
            SessionRepository<S> repository, Consumer<Session> initialise) {
        S session = repository.createSession();
        initialise.accept(session);
        repository.save(session);
        return session.getId();
    }
}
