package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FakePspTokenMaskingTest {

    private final FakePspTokenMasking masking = new FakePspTokenMasking();

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "/api/fake-psp/payments/s3cr3t-token | /api/fake-psp/payments/{token}",
                "/api/fake-psp/payments/s3cr3t-token/complete | /api/fake-psp/payments/{token}/complete",
                "/api/fake-psp/payments/s3cr3t-token/ | /api/fake-psp/payments/{token}/",
                "/api/fake-psp/%70ayments/s3cr3t-token | /api/fake-psp/payments/{token}",
                "/api/fake-psp/%70ayments/%73cr3t-token/complete | /api/fake-psp/payments/{token}/complete",
                "/api/fake-psp/payments;x=1/s3cr3t-token | /api/fake-psp/payments/{token}",
                "/api/fake-psp/payments/s3cr3t-token;jsessionid=x | /api/fake-psp/payments/{token}",
                "/api/fake-psp//payments//s3cr3t-token | /api/fake-psp/payments/{token}",
                "/api/fake-psp/./payments/s3cr3t-token | /api/fake-psp/payments/{token}",
                "/api/fake-psp/other/../payments/s3cr3t-token | /api/fake-psp/payments/{token}",
                "//api/fake-psp/payments/s3cr3t-token | /api/fake-psp/payments/{token}"
            })
    void everySpellingOfThePaymentPathIsMaskedInBothPathKeys(String raw, String expected) {
        for (String key : FakePspTokenMasking.PATH_KEYS) {
            assertThat(valueAfterMasking(key, raw)).as(key).isEqualTo(expected);
        }
    }

    @Test
    void otherUrlsAreLeftAlone() {
        assertThat(valueAfterMasking("http.url", "/api/products")).isEqualTo("/api/products");
    }

    @Test
    void aPathThatCannotBeDecodedIsReplacedEntirely() {
        assertThat(valueAfterMasking("http.url", "/api/fake-psp/%70ayments/s3cr3t%zz"))
                .isEqualTo("[undecodable path]");
    }

    @Test
    void anObservationWithoutAUrlIsLeftAlone() {
        Observation.Context context = new Observation.Context();

        assertThat(masking.map(context).getHighCardinalityKeyValue("http.url")).isNull();
    }

    private String valueAfterMasking(String key, String value) {
        Observation.Context context = new Observation.Context();
        context.addHighCardinalityKeyValue(KeyValue.of(key, value));

        return masking.map(context).getHighCardinalityKeyValue(key).getValue();
    }
}
