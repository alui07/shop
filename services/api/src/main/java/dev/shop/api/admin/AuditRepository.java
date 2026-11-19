package dev.shop.api.admin;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Insert and read only: the table's triggers refuse everything else. */
@Repository
class AuditRepository {

    record Row(
            long id,
            OffsetDateTime occurredAt,
            String actorSubject,
            String actorName,
            String action,
            String entityType,
            long entityId,
            String traceId,
            String changesJson) {}

    private final JdbcClient jdbc;

    AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(
            String actorSubject,
            String actorName,
            String action,
            String entityType,
            long entityId,
            String changesJson,
            String traceId) {
        jdbc.sql("""
                        INSERT INTO admin_audit_event
                            (actor_subject, actor_name, action, entity_type, entity_id, changes, trace_id)
                        VALUES (:subject, :name, :action, :entityType, :entityId, CAST(:changes AS jsonb), :traceId)
                        """)
                .param("subject", actorSubject)
                .param("name", actorName)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("changes", changesJson)
                .param("traceId", traceId)
                .update();
    }

    List<Row> page(int page, int size) {
        return jdbc.sql("""
                        SELECT id, occurred_at, actor_subject, actor_name, action, entity_type, entity_id, trace_id,
                               changes::text AS changes_json
                        FROM admin_audit_event ORDER BY id DESC LIMIT :limit OFFSET :offset
                        """)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, rowNum) -> new Row(
                        rs.getLong("id"),
                        rs.getObject("occurred_at", OffsetDateTime.class),
                        rs.getString("actor_subject"),
                        rs.getString("actor_name"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getLong("entity_id"),
                        rs.getString("trace_id"),
                        rs.getString("changes_json")))
                .list();
    }

    long count() {
        return jdbc.sql("SELECT count(*) FROM admin_audit_event")
                .query(Long.class)
                .single();
    }
}
