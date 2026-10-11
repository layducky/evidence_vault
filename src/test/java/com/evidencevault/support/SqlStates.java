package com.evidencevault.support;

import java.sql.SQLException;

/** Finds the Postgres SQLState behind Spring's translated exceptions, e.g. 23514 for a CHECK violation. */
public final class SqlStates {

    private SqlStates() {
    }

    public static String of(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
