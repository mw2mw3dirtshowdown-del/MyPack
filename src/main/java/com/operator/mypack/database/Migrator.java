package com.operator.mypack.database;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Applies the versioned, idempotent SQL scripts in {@code db/migrations}. The applied versions are recorded in
 * {@code <prefix>schema_version}; running the migrator again is a no-op.
 */
public final class Migrator {

    /** A migration script on the classpath. Versions must be strictly increasing. */
    public record Migration(int version, String name, String resource) {
    }

    /** All migrations in the order they are applied. New schema changes are appended here as new files. */
    public static final List<Migration> ALL = List.of(
            new Migration(1, "init", "db/migrations/V001__init.sql"),
            new Migration(2, "placed_content", "db/migrations/V002__placed_content.sql"));

    private Migrator() {
    }

    /** @return the migrations that were applied by this call (empty when the schema was already current) */
    public static List<Migration> migrate(DataSource dataSource, String prefix, Logger log) throws SQLException, IOException {
        return migrate(dataSource, prefix, log, ALL);
    }

    public static List<Migration> migrate(DataSource dataSource, String prefix, Logger log, List<Migration> migrations)
            throws SQLException, IOException {
        List<Migration> applied = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + prefix + "schema_version ("
                        + "version INTEGER NOT NULL PRIMARY KEY, "
                        + "migration_name VARCHAR(100) NOT NULL, "
                        + "applied_at BIGINT NOT NULL)");
            }
            Set<Integer> done = new HashSet<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT version FROM " + prefix + "schema_version")) {
                while (rs.next()) {
                    done.add(rs.getInt(1));
                }
            }
            int last = 0;
            for (Migration migration : migrations) {
                if (migration.version() <= last) {
                    throw new IllegalStateException("migration versions must be strictly increasing: " + migration);
                }
                last = migration.version();
                if (done.contains(migration.version())) {
                    continue;
                }
                apply(connection, prefix, migration, log);
                applied.add(migration);
            }
        }
        return applied;
    }

    private static void apply(Connection connection, String prefix, Migration migration, Logger log)
            throws SQLException, IOException {
        List<String> statements = statements(read(migration.resource()), prefix);
        connection.setAutoCommit(false);
        try {
            try (Statement st = connection.createStatement()) {
                for (String sql : statements) {
                    st.execute(sql);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + prefix + "schema_version (version, migration_name, applied_at) VALUES (?, ?, ?)")) {
                insert.setInt(1, migration.version());
                insert.setString(2, migration.name());
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
            connection.commit();
            log.info("Applied database migration V" + migration.version() + " (" + migration.name() + ")");
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = Migrator.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing migration script " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Splits a script into statements: drops {@code --} comment lines, substitutes {@code {p}} and splits on ';'. */
    static List<String> statements(String script, String prefix) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : cleaned.toString().replace("{p}", prefix).split(";")) {
            String sql = part.strip();
            if (!sql.isEmpty()) {
                out.add(sql);
            }
        }
        return out;
    }
}
