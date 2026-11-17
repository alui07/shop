package dev.shop.api.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class HttpGet {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpGet() {}

    public static HttpResponse<String> get(int port, String path) {
        return get(port, path, Map.of());
    }

    public static HttpResponse<String> get(int port, String path, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        HttpRequest request = builder.build();
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("GET " + path + " on port " + port + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GET " + path + " on port " + port + " was interrupted", e);
        }
    }

    public static int statusOf(int port, String path) {
        return get(port, path).statusCode();
    }
}
