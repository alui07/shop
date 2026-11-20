package dev.shop.api.support;

import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A signed-in browser that sends JSON with the CSRF header on unsafe requests. */
public final class AdminClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TestBrowser browser;

    public AdminClient(TestBrowser browser) {
        this.browser = browser;
        browser.get("/api/me");
    }

    public HttpResponse<String> get(String path, String... headers) {
        return browser.get(path, headers);
    }

    public HttpResponse<String> post(String path, String body, String... headers) {
        return sendJson("POST", path, body, headers);
    }

    public HttpResponse<String> put(String path, String body, String... headers) {
        return sendJson("PUT", path, body, headers);
    }

    /** A JSON request that deliberately leaves out the CSRF header. */
    public HttpResponse<String> postWithoutCsrf(String path, String body, String... headers) {
        String[] all = new String[headers.length + 2];
        System.arraycopy(headers, 0, all, 0, headers.length);
        all[headers.length] = "Content-Type";
        all[headers.length + 1] = "application/json";
        return browser.send("POST", path, body, all);
    }

    public HttpResponse<String> sendJson(String method, String path, String body, String... headers) {
        String[] all = new String[headers.length + 4];
        System.arraycopy(headers, 0, all, 0, headers.length);
        all[headers.length] = "Content-Type";
        all[headers.length + 1] = "application/json";
        all[headers.length + 2] = "X-XSRF-TOKEN";
        all[headers.length + 3] = browser.cookie(TestBrowser.CSRF_COOKIE);
        return browser.send(method, path, body, all);
    }

    public static JsonNode json(HttpResponse<String> response) {
        return parse(response.body());
    }

    public static JsonNode parse(String json) {
        return JSON.readTree(json);
    }
}
