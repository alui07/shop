package dev.shop.api.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class RequestBodyLimitConfiguration {

    /** Ahead of the session and security filters, so an oversized body costs no session lookup. */
    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(JsonMapper jsonMapper) {
        FilterRegistrationBean<RequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodyLimitFilter(jsonMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
