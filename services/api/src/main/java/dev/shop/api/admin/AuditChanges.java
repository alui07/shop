package dev.shop.api.admin;

import dev.shop.api.catalog.ManagedProduct;
import dev.shop.api.inventory.StockLevel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** What an audit row records: changed fields only, as {@code {"field": {"from": x, "to": y}}}. */
final class AuditChanges {

    private final Map<String, Map<String, Object>> fields = new TreeMap<>();

    private AuditChanges() {}

    static Map<String, Map<String, Object>> productCreated(ManagedProduct created) {
        AuditChanges changes = new AuditChanges();
        changes.record("slug", null, created.slug());
        changes.record("name", null, created.name());
        changes.record("description", null, created.description());
        changes.record("priceMinor", null, created.price().amountMinor());
        changes.record("categorySlug", null, created.category().slug());
        changes.record("active", null, created.active());
        return changes.fields;
    }

    static Map<String, Map<String, Object>> productUpdated(ManagedProduct before, ManagedProduct after) {
        AuditChanges changes = new AuditChanges();
        changes.recordIfChanged("name", before.name(), after.name());
        changes.recordIfChanged("description", before.description(), after.description());
        changes.recordIfChanged(
                "priceMinor", before.price().amountMinor(), after.price().amountMinor());
        changes.recordIfChanged(
                "categorySlug", before.category().slug(), after.category().slug());
        changes.recordIfChanged("active", before.active(), after.active());
        return changes.fields;
    }

    /** The reserved quantity is recorded although it does not change: it is the context for the new quantity. */
    static Map<String, Map<String, Object>> stockSet(StockLevel before, StockLevel after) {
        AuditChanges changes = new AuditChanges();
        changes.record("onHand", before.onHand(), after.onHand());
        changes.record("reserved", before.reserved(), after.reserved());
        return changes.fields;
    }

    private void recordIfChanged(String field, Object from, Object to) {
        if (!Objects.equals(from, to)) {
            record(field, from, to);
        }
    }

    private void record(String field, Object from, Object to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        fields.put(field, change);
    }
}
