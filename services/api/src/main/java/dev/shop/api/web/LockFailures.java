package dev.shop.api.web;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

/** Recognises a lock wait that hit {@code lock_timeout} and a statement that hit {@code statement_timeout}. */
public final class LockFailures {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String QUERY_CANCELED = "57014";

    private LockFailures() {}

    /** Spring translates only some drivers' lock failures, so PostgreSQL's lock_not_available is matched by SQLState too. */
    public static boolean isLockTimeout(DataAccessException exception) {
        SQLException sql = firstSqlException(exception);
        return exception instanceof PessimisticLockingFailureException
                || (sql != null && LOCK_NOT_AVAILABLE.equals(sql.getSQLState()));
    }

    /**
     * PostgreSQL reports a statement over {@code statement_timeout} as query_canceled. A query timeout with no JDBC
     * exception under it is Valkey's (Lettuce through Spring Data Redis), not the database's.
     */
    static boolean isStatementTimeout(DataAccessException exception) {
        SQLException sql = firstSqlException(exception);
        return sql != null && (QUERY_CANCELED.equals(sql.getSQLState()) || exception instanceof QueryTimeoutException);
    }

    static SQLException firstSqlException(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }
}
