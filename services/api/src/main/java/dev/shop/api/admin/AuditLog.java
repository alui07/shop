package dev.shop.api.admin;

import dev.shop.api.customer.CurrentUser;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Appends to the admin audit log inside the caller's transaction, so a change and its record commit together. */
@Service
@PreAuthorize("hasRole('ADMIN')")
class AuditLog {

    private final AuditRepository repository;
    private final Tracer tracer;
    private final JsonMapper jsonMapper;

    AuditLog(AuditRepository repository, Tracer tracer, JsonMapper jsonMapper) {
        this.repository = repository;
        this.tracer = tracer;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(
            CurrentUser actor,
            String action,
            String entityType,
            long entityId,
            Map<String, Map<String, Object>> changes) {
        Span span = tracer.currentSpan();
        String traceId = span == null ? null : span.context().traceId();
        repository.insert(
                actor.subject(),
                actor.displayName(),
                action,
                entityType,
                entityId,
                jsonMapper.writeValueAsString(changes),
                traceId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuditPage page(int page, int size) {
        long totalItems = repository.count();
        List<AuditEvent> items =
                repository.page(page, size).stream().map(this::toEvent).toList();
        int totalPages = (int) ((totalItems + size - 1) / size);
        return new AuditPage(items, page, size, totalItems, totalPages);
    }

    private AuditEvent toEvent(AuditRepository.Row row) {
        return new AuditEvent(
                row.id(),
                row.occurredAt().toInstant(),
                row.actorSubject(),
                row.actorName(),
                row.action(),
                row.entityType(),
                row.entityId(),
                row.traceId(),
                fieldChanges(jsonMapper.readTree(row.changesJson())));
    }

    private static List<FieldChange> fieldChanges(JsonNode changes) {
        return changes.propertyStream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new FieldChange(
                        entry.getKey(),
                        text(entry.getValue().get("from")),
                        text(entry.getValue().get("to"))))
                .toList();
    }

    private static String text(JsonNode value) {
        return value == null || value.isNull() ? null : value.asString();
    }
}
