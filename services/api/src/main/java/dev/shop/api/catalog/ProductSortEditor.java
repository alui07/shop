package dev.shop.api.catalog;

import java.beans.PropertyEditorSupport;

/**
 * Spring falls back to {@code Enum.valueOf} when a converter fails, which would accept constant names such as
 * {@code PRICE_ASC}; a property editor is the one binding hook that takes precedence and keeps the wire names exact.
 */
class ProductSortEditor extends PropertyEditorSupport {

    @Override
    public void setAsText(String text) {
        setValue(ProductSort.fromWireName(text));
    }
}
