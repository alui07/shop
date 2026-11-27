package dev.shop.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

class IdempotencyKeysTest {

    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final IdempotencyKeys keys =
            new IdempotencyKeys(store, new IdempotencyProperties(Duration.ofHours(24), 1000), Clock.systemUTC());

    private void claim() {
        keys.claim("sub", UUID.randomUUID(), "hash");
    }

    @Test
    void aLockTimeoutWhileClaimingTheKeyIsInProgressWhetherSpringOrOnlyTheSqlStateSaysSo() {
        when(store.claim(any(), any(), any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("busy"))
                .thenThrow(new UncategorizedSQLException("insert", "INSERT", new SQLException("busy", "55P03")));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(this::claim).isInstanceOfSatisfying(IdempotencyProblem.class, problem -> {
                assertThat(problem.getBody().getType()).hasToString("tag:shop.dev,2026:idempotency-in-progress");
                assertThat(problem.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            });
        }
    }

    @Test
    void anyOtherDatabaseFailureWhileClaimingTheKeyIsNotHidden() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("broken");
        when(store.claim(any(), any(), any(), any(), any())).thenThrow(failure);

        assertThatThrownBy(this::claim).isSameAs(failure);
    }

    @Test
    void anUnknownDigestAlgorithmIsRefused() {
        assertThatThrownBy(() -> IdempotencyKeys.digest("text", "NO-SUCH-DIGEST"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(IdempotencyKeys.digest("abc", "SHA-256"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void theRequestHashJoinsTheOperationAndThePartsWithNewlines() {
        assertThat(IdempotencyKeys.requestHash("op", "a", 2)).isEqualTo(IdempotencyKeys.digest("op\na\n2", "SHA-256"));
    }

    @Test
    void theCheckoutRequestHashIsTheOneIssuedBeforeTheMove() {
        assertThat(IdempotencyKeys.requestHash("POST /api/checkout", 3L, 12345L))
                .isEqualTo("48998198355575300d9e933d25ed3b9a2a1ffb48ca1190e02332899b3881fc8c");
    }
}
