package dev.shop.api.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.shop.api.auth.WebSliceSecurity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebSliceSecurity
@WebMvcTest(CatalogController.class)
class CatalogControllerTest {

    private static final ProductSummary SUMMARY =
            new ProductSummary("lamp", "Lamp", new Money(1990, "EUR"), new Category("office", "Office"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogService service;

    @Test
    void listsCategories() throws Exception {
        when(service.categories()).thenReturn(List.of(new Category("office", "Office")));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$[0].slug").value("office"))
                .andExpect(jsonPath("$[0].name").value("Office"));
    }

    @Test
    void searchesWithDefaultsWhenNoParametersAreGiven() throws Exception {
        when(service.search(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ProductPage(List.of(SUMMARY), 0, 12, 1, 1));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(jsonPath("$.items[0].slug").value("lamp"))
                .andExpect(jsonPath("$.items[0].price.amountMinor").value(1990))
                .andExpect(jsonPath("$.items[0].price.currency").value("EUR"))
                .andExpect(jsonPath("$.items[0].category.slug").value("office"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
        verify(service)
                .search(
                        isNull(),
                        isNull(),
                        isNull(),
                        org.mockito.ArgumentMatchers.eq(0),
                        org.mockito.ArgumentMatchers.eq(12));
    }

    @Test
    void passesAllParametersToTheService() throws Exception {
        when(service.search(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ProductPage(List.of(), 2, 24, 0, 0));

        mockMvc.perform(get("/api/products?q=lamp&category=office&sort=price_asc&page=2&size=24"))
                .andExpect(status().isOk());

        verify(service).search("lamp", "office", ProductSort.PRICE_ASC, 2, 24);
    }

    @Test
    void acceptsTheBoundaryValues() throws Exception {
        when(service.search(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new ProductPage(List.of(), 10000, 48, 0, 0));

        mockMvc.perform(get("/api/products?page=10000&size=48&q=" + "a".repeat(100)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products?page=0&size=1")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "size=0",
                "size=49",
                "page=-1",
                "page=10001",
                "page=abc",
                "size=abc",
                "sort=cheapest",
                "sort=NAME"
            })
    void rejectsInvalidParametersWithProblemDetails(String query) throws Exception {
        mockMvc.perform(get("/api/products?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void rejectsSearchTextOverOneHundredCharacters() throws Exception {
        mockMvc.perform(get("/api/products?q=" + "a".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void servesProductDetail() throws Exception {
        when(service.product("lamp"))
                .thenReturn(new ProductDetail(
                        "lamp", "Lamp", "Bright", new Money(1990, "EUR"), new Category("office", "Office")));

        mockMvc.perform(get("/api/products/lamp"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(jsonPath("$.description").value("Bright"));
    }

    @Test
    void anUnknownProductIsProblemDetailsNotFound() throws Exception {
        when(service.product("nope")).thenThrow(new ProductNotFoundException());

        mockMvc.perform(get("/api/products/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Product not found"));
    }

    @Test
    void anUnexpectedFailureIsAGenericProblemDetailsWithoutInternals() throws Exception {
        when(service.categories()).thenThrow(new IllegalStateException("connection to 10.0.0.5 refused"));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(
                        content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("10.0.0.5"))));
    }
}
