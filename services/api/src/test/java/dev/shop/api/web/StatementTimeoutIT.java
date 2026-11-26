package dev.shop.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.shop.api.support.AuthIT;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Every pooled connection carries statement_timeout; a statement that exceeds it ends as a 503, not a 500. */
@ExtendWith(OutputCaptureExtension.class)
@Import(StatementTimeoutIT.SlowStatementController.class)
class StatementTimeoutIT extends AuthIT {

    @RestController
    static class SlowStatementController {

        private final JdbcClient jdbc;
        private final TransactionTemplate transaction;

        SlowStatementController(JdbcClient jdbc, PlatformTransactionManager transactions) {
            this.jdbc = jdbc;
            this.transaction = new TransactionTemplate(transactions);
        }

        /** Under /api/products so the security chain lets an anonymous request through. */
        @GetMapping("/api/products/slow-statement")
        String slowStatement() {
            transaction.executeWithoutResult(status -> {
                jdbc.sql("SET LOCAL statement_timeout = '50ms'").update();
                jdbc.sql("SELECT pg_sleep(2), 'secret product text'").query().listOfRows();
            });
            return "unreachable";
        }
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void everyPooledConnectionHasAFiveSecondStatementTimeout() {
        assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single())
                .isEqualTo("5s");
    }

    @Test
    void aSlowStatementOverHttpIsAServiceUnavailableProblemThatLogsNoSql(CapturedOutput output) {
        var response = browser().get("/api/products/slow-statement");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
        assertThat(response.body()).contains("The request took too long; try again shortly");
        assertThat(output.getAll())
                .contains("SQLState 57014")
                .doesNotContain("pg_sleep")
                .doesNotContain("secret product text");
    }

    @Test
    void aStatementOverASetLocalTimeoutFailsAsAStatementTimeoutAndLeavesThePoolUntouched() throws Exception {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        AtomicInteger timedOutBackend = new AtomicInteger();

        Throwable failure = catchThrowable(() -> transaction.executeWithoutResult(status -> {
            timedOutBackend.set(
                    jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
            jdbc.sql("SET LOCAL statement_timeout = '50ms'").update();
            jdbc.sql("SELECT pg_sleep(2), 'secret product text'").query().listOfRows();
        }));

        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(LockFailures.isStatementTimeout((DataAccessException) failure))
                .isTrue();

        Map<Integer, String> timeoutByBackend = timeoutOfEveryPooledConnection();
        assertThat(timeoutByBackend).containsKey(timedOutBackend.get());
        assertThat(timeoutByBackend.values())
                .as("SET LOCAL ended with its transaction, on the connection that timed out too")
                .containsOnly("5s");
    }

    /** Holds ten pool connections at once, more than the idle ones, so that every backend is seen. */
    private Map<Integer, String> timeoutOfEveryPooledConnection() throws Exception {
        List<Connection> held = new ArrayList<>();
        Map<Integer, String> seen = new HashMap<>();
        try {
            for (int i = 0; i < 10; i++) {
                Connection connection = dataSource.getConnection();
                held.add(connection);
                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery(
                                "SELECT pg_backend_pid(), current_setting('statement_timeout')")) {
                    rows.next();
                    seen.put(rows.getInt(1), rows.getString(2));
                }
            }
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
        return seen;
    }
}
