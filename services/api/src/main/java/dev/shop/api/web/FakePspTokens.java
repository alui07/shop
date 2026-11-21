package dev.shop.api.web;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

/** Hides the capability token of the fake payment page wherever a request path is recorded. */
final class FakePspTokens {

    static final String UNDECODABLE_PATH = "[undecodable path]";

    private static final String PREFIX = "/api/fake-psp/payments/";
    private static final Pattern PATH_PARAMETERS = Pattern.compile(";[^/]*");
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");

    private FakePspTokens() {}

    /**
     * Matching is on the path the way Spring routes it: path parameters dropped, percent-escapes decoded, slashes and
     * dot segments collapsed. A path that cannot be decoded is replaced entirely; other paths are returned as given.
     */
    static String mask(String uri, String placeholder) {
        String path = routedPath(uri);
        if (path == null) {
            return UNDECODABLE_PATH;
        }
        if (!path.startsWith(PREFIX)) {
            return uri;
        }
        int end = path.indexOf('/', PREFIX.length());
        return PREFIX + placeholder + (end < 0 ? "" : path.substring(end));
    }

    private static String routedPath(String uri) {
        try {
            String decoded = UriUtils.decode(PATH_PARAMETERS.matcher(uri).replaceAll(""), StandardCharsets.UTF_8);
            return StringUtils.cleanPath(REPEATED_SLASHES.matcher(decoded).replaceAll("/"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
