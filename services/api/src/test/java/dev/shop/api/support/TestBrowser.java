package dev.shop.api.support;

import com.nimbusds.jwt.JWTClaimsSet;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A minimal browser for the API: it keeps cookies (ignoring {@code Secure}, since the tests speak plain http), never
 * follows redirects, and can walk the OpenID Connect login against the stub identity provider.
 */
public final class TestBrowser {

    public static final String SESSION_COOKIE = "__Host-SESSION";
    public static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final int port;
    private final StubIdentityProvider idp;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public TestBrowser(int port, StubIdentityProvider idp) {
        this.port = port;
        this.idp = idp;
    }

    public HttpResponse<String> get(String path, String... headers) {
        return send("GET", path, null, headers);
    }

    public HttpResponse<String> post(String path, String body, String... headers) {
        return send("POST", path, body, headers);
    }

    /** An unsafe request carrying the CSRF header taken from the current cookie. */
    public HttpResponse<String> postWithCsrf(String path, String body, String... headers) {
        List<String> all = new ArrayList<>(List.of(headers));
        all.addAll(List.of("X-XSRF-TOKEN", cookie(CSRF_COOKIE)));
        return post(path, body, all.toArray(String[]::new));
    }

    public HttpResponse<String> send(String method, String path, String body, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (!cookies.isEmpty()) {
            builder.header(
                    "Cookie",
                    cookies.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining("; ")));
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            remember(response);
            return response;
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    public void clearCookies() {
        cookies.clear();
    }

    /** Signs in through the stub identity provider; returns the response of the login callback. */
    public HttpResponse<String> login(Identity identity) {
        return login(identity, claims -> {});
    }

    public HttpResponse<String> login(Identity identity, Consumer<JWTClaimsSet.Builder> tweak) {
        return login(new StubIdentityProvider.Login(identity, tweak, StubIdentityProvider.Signing.VALID));
    }

    /** A template without a nonce signs in with the nonce the API put in the authorization request. */
    public HttpResponse<String> login(StubIdentityProvider.Login template) {
        HttpResponse<String> start = get("/oauth2/authorization/keycloak");
        Map<String, String> authorizeQuery = query(location(start));
        String nonce = template.nonce() != null ? template.nonce() : authorizeQuery.get("nonce");
        String code = idp.issueCode(new StubIdentityProvider.Login(
                template.identity(),
                nonce,
                authorizeQuery.get("code_challenge"),
                template.tweak(),
                template.signing()));
        return get("/login/oauth2/code/keycloak?code=" + code + "&state=" + authorizeQuery.get("state"));
    }

    public Map<String, String> startLogin() {
        return query(location(get("/oauth2/authorization/keycloak")));
    }

    public static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location").orElseThrow();
    }

    public static Map<String, String> query(String url) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            parameters.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private void remember(HttpResponse<?> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0];
            int separator = pair.indexOf('=');
            String name = pair.substring(0, separator);
            String value = pair.substring(separator + 1);
            boolean expired = header.toLowerCase().contains("max-age=0") || value.isEmpty();
            if (expired) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }
}
