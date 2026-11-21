package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.FlashMap;

class NoFlashMapManagerConfigurationTest {

    @Test
    void theManagerNeverTouchesTheSession() {
        var manager = new NoFlashMapManagerConfiguration().flashMapManager();
        MockHttpServletRequest request = new MockHttpServletRequest();

        FlashMap retrieved = manager.retrieveAndUpdate(request, new MockHttpServletResponse());
        assertThat((Object) retrieved).isNull();
        manager.saveOutputFlashMap(new FlashMap(), request, new MockHttpServletResponse());

        assertThat(request.getSession(false)).isNull();
    }
}
