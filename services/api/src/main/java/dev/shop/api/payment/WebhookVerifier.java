package dev.shop.api.payment;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Checks {@code Shop-Signature: t=<1 to 10 digits>,v1=<64 lowercase hex>} over the raw body. The answer to the caller
 * is always the same; the reason it was refused is only for the log and never contains the header value.
 */
@Component
class WebhookVerifier {

    static final long TOLERANCE_SECONDS = 300;

    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,10}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final WebhookSigner signer;
    private final Clock clock;

    WebhookVerifier(WebhookSigner signer, Clock clock) {
        this.signer = signer;
        this.clock = clock;
    }

    /** Empty when the signature is valid, else why not. */
    Optional<String> refusal(Enumeration<String> headerValues, byte[] body) throws GeneralSecurityException {
        if (!headerValues.hasMoreElements()) {
            return Optional.of("no signature header");
        }
        String header = headerValues.nextElement();
        if (headerValues.hasMoreElements()) {
            return Optional.of("more than one signature header");
        }
        String timestamp = null;
        String digest = null;
        for (String element : header.split(",", -1)) {
            if (element.startsWith("t=") && timestamp == null) {
                timestamp = element.substring(2);
            } else if (element.startsWith("v1=") && digest == null) {
                digest = element.substring(3);
            } else {
                return Optional.of("unknown or repeated element");
            }
        }
        if (timestamp == null || digest == null) {
            return Optional.of("missing element");
        }
        if (!TIMESTAMP.matcher(timestamp).matches() || !DIGEST.matcher(digest).matches()) {
            return Optional.of("malformed element");
        }
        long signedAt = Long.parseLong(timestamp);
        if (Math.abs(clock.instant().getEpochSecond() - signedAt) > TOLERANCE_SECONDS) {
            return Optional.of("timestamp outside the tolerance");
        }
        boolean valid =
                MessageDigest.isEqual(signer.mac(signedAt, body), HexFormat.of().parseHex(digest));
        return valid ? Optional.empty() : Optional.of("signature mismatch");
    }
}
