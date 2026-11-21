package dev.shop.api.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** One implementation of the signature for both sides: the provider signs with it and the webhook verifies with it. */
@Component
public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    WebhookSigner(PaymentProperties properties) {
        this.secret = properties.webhookSecret().getBytes(StandardCharsets.UTF_8);
    }

    /** The {@code Shop-Signature} header value: {@code t=<unix seconds>,v1=<hex>}. */
    public String header(Instant signedAt, byte[] body) throws GeneralSecurityException {
        long seconds = signedAt.getEpochSecond();
        return "t=" + seconds + ",v1=" + HexFormat.of().formatHex(mac(seconds, body));
    }

    byte[] mac(long seconds, byte[] body) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(secret, ALGORITHM));
        mac.update((seconds + ".").getBytes(StandardCharsets.US_ASCII));
        return mac.doFinal(body);
    }
}
