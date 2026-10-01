package com.operator.mypack.database;

import com.operator.mypack.config.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialectTest {

    private static Settings.Database cfg(String host, String name, boolean ssl) {
        return new Settings.Database(Settings.DatabaseType.MYSQL, "x.db", host, 3306, name, "u", "p", ssl,
                "mypack_", 6, 1, 10_000, 1_800_000);
    }

    @Test
    @DisplayName("upsert SQL differs per dialect but keeps parameter order key-then-values")
    void upsertSql() {
        List<String> key = List.of("id");
        List<String> values = List.of("a", "b");
        assertEquals("INSERT INTO t (id, a, b) VALUES (?, ?, ?) ON CONFLICT (id) DO UPDATE SET a = excluded.a, b = excluded.b",
                Dialect.SQLITE.upsert("t", key, values));
        assertEquals("INSERT INTO t (id, a, b) VALUES (?, ?, ?) ON CONFLICT (id) DO UPDATE SET a = excluded.a, b = excluded.b",
                Dialect.POSTGRESQL.upsert("t", key, values));
        assertEquals("INSERT INTO t (id, a, b) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE a = VALUES(a), b = VALUES(b)",
                Dialect.MYSQL.upsert("t", key, values));
    }

    @Test
    @DisplayName("key-only upserts become DO NOTHING / INSERT IGNORE")
    void keyOnlyUpsert() {
        assertTrue(Dialect.SQLITE.upsert("t", List.of("id"), List.of()).endsWith("ON CONFLICT (id) DO NOTHING"));
        assertTrue(Dialect.MYSQL.upsert("t", List.of("id"), List.of()).startsWith("INSERT IGNORE INTO t"));
    }

    @Test
    @DisplayName("JDBC URLs are built from validated parts")
    void urls() {
        assertEquals("jdbc:mysql://db.example.com:3306/mypack?sslMode=DISABLED&allowPublicKeyRetrieval=true&useUnicode=true"
                        + "&characterEncoding=UTF-8&serverTimezone=UTC",
                Dialect.MYSQL.jdbcUrl(cfg("db.example.com", "mypack", false), null));
        assertTrue(Dialect.MYSQL.jdbcUrl(cfg("h", "d", true), null).contains("sslMode=REQUIRED"));
        assertEquals("jdbc:postgresql://pg:3306/mypack?sslmode=disable",
                Dialect.POSTGRESQL.jdbcUrl(cfg("pg", "mypack", false), null));
        String sqlite = Dialect.SQLITE.jdbcUrl(cfg("h", "d", false), Path.of("/data/plugins/MyPack/mypack.db"));
        assertTrue(sqlite.startsWith("jdbc:sqlite:") && sqlite.contains("journal_mode=WAL"), sqlite);
    }

    @Test
    @DisplayName("host and database names cannot smuggle extra connection parameters")
    void rejectsInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> Dialect.MYSQL.jdbcUrl(cfg("evil.com/db?allowLoadLocalInfile=true&x=", "mypack", false), null));
        assertThrows(IllegalArgumentException.class,
                () -> Dialect.POSTGRESQL.jdbcUrl(cfg("host", "db?sslmode=disable", false), null));
        assertThrows(IllegalArgumentException.class,
                () -> Dialect.MYSQL.jdbcUrl(cfg("host", "a b", false), null));
    }

    @Test
    @DisplayName("driver class names match the drivers Paper bundles (or the PostgreSQL driver)")
    void driverClasses() {
        assertEquals("org.sqlite.JDBC", Dialect.SQLITE.driverClass());
        assertEquals("com.mysql.cj.jdbc.Driver", Dialect.MYSQL.driverClass());
        assertEquals("org.postgresql.Driver", Dialect.POSTGRESQL.driverClass());
    }
}
