package dev.shop.api.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

/** A request field has the JSON type the contract says: {@code 12.5} is not an integer and {@code "true"} is not a boolean. */
@Configuration(proxyBeanMethods = false)
class JsonCoercionConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictScalars() {
        return builder -> builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
}
