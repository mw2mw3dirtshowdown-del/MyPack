package com.operator.mypack.database;

import java.sql.SQLException;

/** A unit of JDBC work that may throw {@link SQLException}. */
@FunctionalInterface
public interface SqlFunction<T, R> {
    R apply(T value) throws SQLException;
}
