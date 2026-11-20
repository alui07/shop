package dev.shop.api.web;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;

/** Recognises a lock wait that hit {@code lock_timeout}. */
public final class LockFailures {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private LockFailures() {}

    /** Spring translates only some drivers' lock failures, so PostgreSQL's lock_not_available is matched by SQLState too. */
    public static boolean isLockTimeout(DataAccessException exception) {
        SQLException sql = firstSqlException(exception);
        return exception instanceof PessimisticLockingFailureException
                || (sql != null && LOCK_NOT_AVAILABLE.equals(sql.getSQLState()));
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
