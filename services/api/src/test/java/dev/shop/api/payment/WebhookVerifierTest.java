package dev.shop.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final byte[] BODY = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    private static final String DIGEST = "a".repeat(64);

    private final WebhookSigner signer =
            new WebhookSigner(new PaymentProperties("fake", "unit-test-secret-unit-test-secret-1234"));
    private final WebhookVerifier verifier = new WebhookVerifier(signer, Clock.fixed(NOW, ZoneOffset.UTC));

    private String refusalFor(String... headers) throws GeneralSecurityException {
        Enumeration<String> values = Collections.enumeration(List.of(headers));
        return verifier.refusal(values, BODY).orElse("accepted");
    }

    private String signedAt(Instant at) throws GeneralSecurityException {
        return signer.header(at, BODY);
    }

    @Test
    void aSignatureMadeNowIsAccepted() throws Exception {
        assertThat(refusalFor(signedAt(NOW))).isEqualTo("accepted");
    }

    @Test
    void theToleranceIsThreeHundredSecondsEitherWay() throws Exception {
        assertThat(refusalFor(signedAt(NOW.minusSeconds(300)))).isEqualTo("accepted");
        assertThat(refusalFor(signedAt(NOW.plusSeconds(300)))).isEqualTo("accepted");
        assertThat(refusalFor(signedAt(NOW.minusSeconds(301)))).isEqualTo("timestamp outside the tolerance");
        assertThat(refusalFor(signedAt(NOW.plusSeconds(301)))).isEqualTo("timestamp outside the tolerance");
    }

    @Test
    void aMissingOrRepeatedHeaderIsRefused() throws Exception {
        assertThat(refusalFor()).isEqualTo("no signature header");
        assertThat(refusalFor(signedAt(NOW), signedAt(NOW))).isEqualTo("more than one signature header");
    }

    @Test
    void anElementThatIsUnknownOrRepeatedIsRefused() throws Exception {
        long t = NOW.getEpochSecond();
        assertThat(refusalFor("t=" + t + ",t=" + t + ",v1=" + DIGEST)).isEqualTo("unknown or repeated element");
        assertThat(refusalFor("t=" + t + ",v1=" + DIGEST + ",v1=" + DIGEST)).isEqualTo("unknown or repeated element");
        assertThat(refusalFor("t=" + t + ",v1=" + DIGEST + ",v2=" + DIGEST)).isEqualTo("unknown or repeated element");
        assertThat(refusalFor("t=" + t + ",,v1=" + DIGEST)).isEqualTo("unknown or repeated element");
    }

    @Test
    void aMissingElementIsRefused() throws Exception {
        assertThat(refusalFor("t=" + NOW.getEpochSecond())).isEqualTo("missing element");
        assertThat(refusalFor("v1=" + DIGEST)).isEqualTo("missing element");
    }

    @Test
    void anElementThatIsNotInTheExactShapeIsRefused() throws Exception {
        long t = NOW.getEpochSecond();
        assertThat(refusalFor("t=" + t + ",v1=" + "A".repeat(64))).isEqualTo("malformed element");
        assertThat(refusalFor("t=" + t + ",v1=" + "a".repeat(63))).isEqualTo("malformed element");
        assertThat(refusalFor("t=-" + t + ",v1=" + DIGEST)).isEqualTo("malformed element");
        assertThat(refusalFor("t=12345678901,v1=" + DIGEST)).isEqualTo("malformed element");
        assertThat(refusalFor("t=,v1=" + DIGEST)).isEqualTo("malformed element");
    }

    @Test
    void aWrongDigestIsRefused() throws Exception {
        assertThat(refusalFor("t=" + NOW.getEpochSecond() + ",v1=" + DIGEST)).isEqualTo("signature mismatch");
    }
}
