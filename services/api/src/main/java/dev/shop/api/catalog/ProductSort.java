package dev.shop.api.catalog;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

enum ProductSort {
    RELEVANCE("relevance", "ts_rank(p.search_vector, websearch_to_tsquery('english', :q)) DESC, p.name, p.slug"),
    NAME("name", "p.name, p.slug"),
    PRICE_ASC("price_asc", "p.price_minor, p.name, p.slug"),
    PRICE_DESC("price_desc", "p.price_minor DESC, p.name, p.slug");

    private final String wireName;
    private final String orderBy;

    ProductSort(String wireName, String orderBy) {
        this.wireName = wireName;
        this.orderBy = orderBy;
    }

    @JsonValue
    String wireName() {
        return wireName;
    }

    String orderBy() {
        return orderBy;
    }

    static ProductSort fromWireName(String wireName) {
        return Arrays.stream(values())
                .filter(sort -> sort.wireName.equals(wireName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown sort '" + wireName + "'"));
    }

    /** Relevance needs a search text; without one it falls back to name. */
    static ProductSort resolve(ProductSort requested, boolean searching) {
        ProductSort chosen = requested != null ? requested : (searching ? RELEVANCE : NAME);
        return chosen == RELEVANCE && !searching ? NAME : chosen;
    }
}
