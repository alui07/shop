package dev.shop.api.admin;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shop.api.catalog.AdminProductPage;
import dev.shop.api.catalog.ProductAdministration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The URL rule is bypassed here (everything permitted) so only the method-level check is under test. */
@WebMvcTest(AdminProductController.class)
class MethodSecurityDenialTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductAdministration products;

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void aCustomerIsDeniedByMethodSecurityWithAProblem() throws Exception {
        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Access is denied"));
    }

    @Test
    void anAnonymousCallerIsDeniedByMethodSecurityToo() throws Exception {
        mockMvc.perform(get("/api/admin/products")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminPasses() throws Exception {
        when(products.list(null, 0, 20)).thenReturn(new AdminProductPage(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class NoUrlRules {

        @Bean
        SecurityFilterChain permitEverything(HttpSecurity http) {
            return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }
    }
}
