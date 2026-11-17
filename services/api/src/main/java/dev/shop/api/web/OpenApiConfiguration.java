package dev.shop.api.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI shopOpenApi() {
        return new OpenAPI().info(new Info().title("Shop API").version("1"));
    }

    @Bean
    OpenApiCustomizer requiredPropertiesCustomizer() {
        return new RequiredPropertiesCustomizer();
    }
}
