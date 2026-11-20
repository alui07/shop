package dev.shop.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.shop.api.customer.CurrentUser;
import io.micrometer.tracing.Tracer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuditLogTest {

    private final AuditRepository repository = mock(AuditRepository.class);
    private final Tracer tracer = mock(Tracer.class);
    private final AuditLog audit =
            new AuditLog(repository, tracer, JsonMapper.builder().build());

    @Test
    void storesNoTraceIdWhenThereIsNoCurrentSpan() {
        when(tracer.currentSpan()).thenReturn(null);

        audit.record(new CurrentUser("sub", "Name"), "stock.set", "stock", 4, Map.of());

        verify(repository).insert(eq("sub"), eq("Name"), eq("stock.set"), eq("stock"), eq(4L), eq("{}"), eq(null));
    }

    @Test
    void readsAChangeWithAMissingSideAsNull() {
        when(repository.count()).thenReturn(1L);
        when(repository.page(0, 20))
                .thenReturn(List.of(new AuditRepository.Row(
                        9,
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        "sub",
                        "Name",
                        "product.updated",
                        "product",
                        3,
                        null,
                        "{\"b\":{\"to\":2},\"a\":{\"from\":\"x\",\"to\":null}}")));

        AuditPage page = audit.page(0, 20);

        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(page.items().getFirst().traceId()).isNull();
        assertThat(page.items().getFirst().changes())
                .containsExactly(new FieldChange("a", "x", null), new FieldChange("b", null, "2"));
    }
}
