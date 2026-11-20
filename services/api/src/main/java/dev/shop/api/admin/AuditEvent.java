package dev.shop.api.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

record AuditEvent(
        long id,
        Instant occurredAt,
        String actorSubject,
        String actorName,
        String action,
        String entityType,
        long entityId,

        @Schema(
                nullable = true,
                description =
                        "Trace id of the request, copied from its traceparent header. Unverified: the client can choose it.")
        String traceId,

        List<FieldChange> changes) {}
