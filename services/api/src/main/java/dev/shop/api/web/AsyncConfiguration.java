package dev.shop.api.web;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Event listeners run on the application task executor in every context, tests included. A failure is logged with its
 * class and message only: listener exceptions carry no cause, so no stack trace can leak an address or a token.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfiguration implements AsyncConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncConfiguration.class);

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable failure, Method method, Object... arguments) -> LOGGER.warn(
                "Asynchronous {} failed with {}: {}",
                method.getName(),
                failure.getClass().getSimpleName(),
                failure.getMessage());
    }
}
