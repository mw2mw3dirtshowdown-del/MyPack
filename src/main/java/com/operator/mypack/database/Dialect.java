package com.operator.mypack.database;

import com.operator.mypack.config.Settings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** SQL dialects MyPack can talk to, together with the few statements whose syntax differs between them. */
public enum Dialect {
    SQLITE("org.sqlite.JDBC"),
    MYSQL("com.mysql.cj.jdbc.Driver"),
    POSTGRESQL("org.postgresql.Driver");

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-_:\\[\\]]{1,253}");
    private static final Pattern DB_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    private final String driverClass;

    Dialect(String driverClass) {
        this.driverClass = driverClass;
    }

    public String driverClass() {
        return driverClass;
    }

    public static Dialect of(Settings.DatabaseType type) {
        return switch (type) {
            case SQLITE -> SQLITE;
            case MYSQL -> MYSQL;
            case POSTGRESQL -> POSTGRESQL;
        };
    }

    /**
     * Builds the JDBC URL for the configured database. Host and database names are validated against a strict
     * allow-list so the configuration can never smuggle extra connection parameters into the URL.
     */
    public String jdbcUrl(Settings.Database cfg, Path sqliteFile) {
        switch (this) {
            case SQLITE: {
                String path = sqliteFile.toAbsolutePath().toString().replace('\\', '/');
                return "jdbc:sqlite:" + path + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=on&synchronous=NORMAL";
            }
            case MYSQL: {
                requireSafe(cfg);
                return "jdbc:mysql://" + cfg.host() + ":" + cfg.port() + "/" + cfg.name()
                        + "?sslMode=" + (cfg.useSsl() ? "REQUIRED" : "DISABLED")
                        + "&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC";
            }
            case POSTGRESQL: {
                requireSafe(cfg);
                return "jdbc:postgresql://" + cfg.host() + ":" + cfg.port() + "/" + cfg.name()
                        + "?sslmode=" + (cfg.useSsl() ? "require" : "disable");
            }
            default:
                throw new IllegalStateException("unreachable dialect " + this);
        }
    }

    private static void requireSafe(Settings.Database cfg) {
        if (!HOST.matcher(cfg.host()).matches()) {
            throw new IllegalArgumentException("database.host contains characters that are not allowed in a host name");
        }
        if (!DB_NAME.matcher(cfg.name()).matches()) {
            throw new IllegalArgumentException("database.name may only contain letters, digits, '_' and '-'");
        }
    }

    /**
     * Builds an "insert or update" statement with positional parameters in the order {@code keyColumns} then
     * {@code valueColumns}. Table and column names are developer supplied constants, never user input.
     */
    public String upsert(String table, List<String> keyColumns, List<String> valueColumns) {
        List<String> all = new ArrayList<>(keyColumns);
        all.addAll(valueColumns);
        String columns = String.join(", ", all);
        String marks = String.join(", ", all.stream().map(c -> "?").toList());
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                .append(" (").append(columns).append(") VALUES (").append(marks).append(')');
        if (valueColumns.isEmpty()) {
            return this == MYSQL
                    ? sql.toString().replaceFirst("INSERT INTO", "INSERT IGNORE INTO")
                    : sql.append(" ON CONFLICT (").append(String.join(", ", keyColumns)).append(") DO NOTHING").toString();
        }
        if (this == MYSQL) {
            sql.append(" ON DUPLICATE KEY UPDATE ");
            sql.append(String.join(", ", valueColumns.stream().map(c -> c + " = VALUES(" + c + ")").toList()));
        } else {
            sql.append(" ON CONFLICT (").append(String.join(", ", keyColumns)).append(") DO UPDATE SET ");
            sql.append(String.join(", ", valueColumns.stream().map(c -> c + " = excluded." + c).toList()));
        }
        return sql.toString();
    }
}
