package dev.shop.api.web;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.List;
import org.springframework.stereotype.Component;

/** The fake payment page's token is a bearer credential in the path; exported spans carry a placeholder instead. */
@Component
class FakePspTokenMasking implements ObservationFilter {

    /** Spring's own key and the OpenTelemetry convention's key; both hold the raw request path. */
    static final List<String> PATH_KEYS = List.of("http.url", "url.path");

    @Override
    public Observation.Context map(Observation.Context context) {
        for (String key : PATH_KEYS) {
            KeyValue path = context.getHighCardinalityKeyValue(key);
            if (path != null) {
                context.addHighCardinalityKeyValue(KeyValue.of(key, FakePspTokens.mask(path.getValue(), "{token}")));
            }
        }
        return context;
    }
}
