package dev.shop.api.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI shopOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Shop API").version("1"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "sessionCookie",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.COOKIE)
                                                .name("__Host-SESSION")
                                                .description(
                                                        "Server-side session cookie set after the OpenID Connect login."))
                                .addSecuritySchemes(
                                        "csrfHeader",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-XSRF-TOKEN")
                                                .description(
                                                        "The value of the __Host-XSRF-TOKEN cookie; required on every unsafe request.")));
    }

    @Bean
    OpenApiCustomizer requiredPropertiesCustomizer() {
        return new RequiredPropertiesCustomizer();
    }

    @Bean
    OpenApiCustomizer csrfHeaderCustomizer() {
        return new CsrfHeaderCustomizer();
    }

    @Bean
    OpenApiCustomizer serviceUnavailableResponseCustomizer() {
        return new ServiceUnavailableResponseCustomizer();
    }
}
