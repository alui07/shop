package dev.shop.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shop.api.catalog.Category;
import dev.shop.api.catalog.pricing.Money;
import dev.shop.api.customer.CurrentUser;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The URL rule is bypassed here (everything permitted) so only the method-level checks are under test. */
@WebMvcTest({AdminProductController.class, AdminAuditController.class})
class MethodSecurityDenialTest {

    private static final String PRODUCT_BODY = """
            {"name":"N","description":"D","priceMinor":100,"categorySlug":"c","active":true,"version":0}
            """;
    private static final String NEW_PRODUCT_BODY = """
            {"slug":"s","name":"N","description":"D","priceMinor":100,"categorySlug":"c","active":true}
            """;
    private static final String STOCK_BODY = """
            {"onHand":5,"version":0}
            """;

    private static final AdminProduct PRODUCT = new AdminProduct(
            1, "s", "N", "D", new Money(100, "EUR"), new Category("c", "C"), true, 0, new AdminStock(5, 0, 0));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminProductService products;

    @MockitoBean
    private AuditLog audit;

    static Stream<Arguments> requests() {
        return Stream.of(
                Arguments.of(get("/api/admin/products")),
                Arguments.of(get("/api/admin/products/1")),
                Arguments.of(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_PRODUCT_BODY)),
                Arguments.of(put("/api/admin/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PRODUCT_BODY)),
                Arguments.of(put("/api/admin/products/1/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STOCK_BODY)),
                Arguments.of(get("/api/admin/audit")));
    }

    /** An OIDC login, so {@code CurrentUser.of} would accept it: only the controller's own guard can deny. */
    @ParameterizedTest
    @MethodSource("requests")
    void aCustomerIsDeniedByMethodSecurityWithAProblem(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(csrf())
                        .with(oidcLogin()
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
                                .idToken(token -> token.subject("customer-sub"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Access is denied"));

        verifyNoInteractions(products, audit);
    }

    @ParameterizedTest
    @MethodSource("requests")
    void anAnonymousCallerIsDeniedByMethodSecurityToo(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(csrf())).andExpect(status().isForbidden());

        verifyNoInteractions(products, audit);
    }

    @ParameterizedTest
    @MethodSource("writes")
    @WithMockUser(roles = "ADMIN")
    void anAdminWithoutAnOidcLoginCannotWrite(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access is denied"));

        verifyNoInteractions(products, audit);
    }

    static Stream<Arguments> writes() {
        return requests().skip(2).limit(3);
    }

    @Test
    void anAdminPassesAndTheServiceSeesTheSubjectAndName() throws Exception {
        CurrentUser actor = new CurrentUser("admin-sub", "Alex Admin");
        when(products.list(null, 0, 20)).thenReturn(new AdminProductPage(List.of(), 0, 20, 0, 0));
        when(products.get(1)).thenReturn(PRODUCT);
        when(products.create(eq(actor), any())).thenReturn(PRODUCT);
        when(products.update(eq(actor), eq(1L), any())).thenReturn(PRODUCT);
        when(products.setStock(eq(actor), eq(1L), any())).thenReturn(PRODUCT);
        when(audit.page(0, 20)).thenReturn(new AuditPage(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/admin/products").with(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/admin/products/1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock.onHand").value(5));
        mockMvc.perform(post("/api/admin/products")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_PRODUCT_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/products/1"));
        mockMvc.perform(put("/api/admin/products/1")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PRODUCT_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/admin/products/1/stock")
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STOCK_BODY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/audit").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        verify(products).create(eq(actor), any());
    }

    private static OidcLoginRequestPostProcessor admin() {
        return oidcLogin()
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .idToken(token -> token.subject("admin-sub").claim("name", "Alex Admin"));
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
