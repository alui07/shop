package dev.shop.api.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;

/**
 * An API that never redirects with flash attributes needs no flash map manager. The default one reads the session on
 * every request, which costs a session store call, and fails the webhook when that store is down.
 */
@Configuration(proxyBeanMethods = false)
class NoFlashMapManagerConfiguration {

    @Bean(DispatcherServlet.FLASH_MAP_MANAGER_BEAN_NAME)
    FlashMapManager flashMapManager() {
        return new FlashMapManager() {
            @Override
            public FlashMap retrieveAndUpdate(HttpServletRequest request, HttpServletResponse response) {
                return null;
            }

            @Override
            public void saveOutputFlashMap(
                    FlashMap flashMap, HttpServletRequest request, HttpServletResponse response) {}
        };
    }
}
