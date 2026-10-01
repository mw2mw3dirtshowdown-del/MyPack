package com.operator.mypack.database;

import com.operator.mypack.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against a real SQLite file through a real HikariCP pool. */
class DatabaseTest {

    @TempDir
    Path tmp;

    private DatabaseManager db;

    private static Settings.Database cfg(String prefix) {
        return new Settings.Database(Settings.DatabaseType.SQLITE, "test.db", "127.0.0.1", 3306, "mypack", "u", "p",
                false, prefix, 4, 1, 5_000, 600_000);
    }

    @BeforeEach
    void connect() throws Exception {
        db = new DatabaseManager(Logger.getAnonymousLogger(), getClass().getClassLoader());
        db.connect(cfg("mypack_"), tmp);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private int count(String table) throws SQLException {
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table); ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    @Test
    @DisplayName("migrations create the schema and record every applied version")
    void migrationsApply() throws Exception {
        assertEquals(Migrator.ALL.size(), count("mypack_schema_version"));
        for (String table : List.of("packs", "pack_audit", "player_pack_status", "placed_content")) {
            assertEquals(0, count("mypack_" + table), table + " must exist and be empty");
        }
    }

    @Test
    @DisplayName("migrations are idempotent: running them again changes nothing")
    void migrationsAreIdempotent() throws Exception {
        assertTrue(Migrator.migrate(db.dataSource(), "mypack_", Logger.getAnonymousLogger()).isEmpty());
        // reconnecting (as after a server restart) must not re-apply or fail either
        db.connect(cfg("mypack_"), tmp);
        assertEquals(Migrator.ALL.size(), count("mypack_schema_version"));
    }

    @Test
    @DisplayName("a script that is half applied can be re-run (all statements use IF NOT EXISTS)")
    void halfAppliedMigrationRecovers() throws Exception {
        db.run(c -> {
            try (var st = c.createStatement()) {
                st.execute("DELETE FROM mypack_schema_version WHERE version = 2");
            }
            return null;
        });
        List<Migrator.Migration> applied = Migrator.migrate(db.dataSource(), "mypack_", Logger.getAnonymousLogger());
        assertEquals(1, applied.size());
        assertEquals(2, applied.get(0).version());
    }

    @Test
    @DisplayName("different table prefixes live side by side in one database")
    void prefixIsolation() throws Exception {
        Migrator.migrate(db.dataSource(), "other_", Logger.getAnonymousLogger());
        PackRepository mine = new PackRepository(db);
        mine.upsert(new PackRepository.PackRecord(UUID.randomUUID(), "ns", "Name", "1.0.0", "a.zip", "f", true, 1, 1));
        assertEquals(1, count("mypack_packs"));
        assertEquals(0, count("other_packs"));
    }

    @Test
    @DisplayName("pack repository: upsert inserts then updates, enable flag and delete work")
    void packRepository() throws Exception {
        PackRepository repo = new PackRepository(db);
        UUID id = UUID.randomUUID();
        repo.upsert(new PackRepository.PackRecord(id, "aether", "Aether", "1.0.0", "aether.zip", "fp1", true, 100, 100));
        repo.upsert(new PackRepository.PackRecord(id, "aether", "Aether Reborn", "1.1.0", "aether.zip", "fp2", true, 100, 200));

        List<PackRepository.PackRecord> all = repo.findAll();
        assertEquals(1, all.size(), "second upsert must update, not insert");
        assertEquals("Aether Reborn", all.get(0).name());
        assertEquals("fp2", all.get(0).fingerprint());
        assertEquals(100, all.get(0).installedAt(), "installed_at is the original timestamp");

        repo.setEnabled(id, false, 300);
        Optional<PackRepository.PackRecord> found = repo.findBySource("aether.zip");
        assertTrue(found.isPresent());
        assertFalse(found.get().enabled());
        assertEquals(300, found.get().updatedAt());

        repo.delete(id);
        assertTrue(repo.findAll().isEmpty());
        assertTrue(repo.findBySource("aether.zip").isEmpty());
    }

    @Test
    @DisplayName("all statements are parameterized: hostile text is stored verbatim and nothing is dropped")
    void sqlInjectionIsHarmless() throws Exception {
        PackRepository repo = new PackRepository(db);
        String evil = "x'); DROP TABLE mypack_packs; --";
        repo.upsert(new PackRepository.PackRecord(UUID.randomUUID(), "ns", evil, "1.0.0", evil, "f", true, 1, 1));
        List<PackRepository.PackRecord> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals(evil, all.get(0).name());
        assertTrue(repo.findBySource(evil).isPresent());
        assertTrue(repo.findBySource("' OR '1'='1").isEmpty());
    }

    @Test
    @DisplayName("audit log keeps newest first and prune removes old rows")
    void auditRepository() throws Exception {
        AuditRepository audit = new AuditRepository(db);
        audit.log(null, "reload", "console", "first");
        Thread.sleep(5);
        audit.log(UUID.randomUUID(), "install", "Steve", "second");
        List<AuditRepository.AuditEntry> recent = audit.recent(10);
        assertEquals(2, recent.size());
        assertEquals("install", recent.get(0).action());
        assertEquals("reload", recent.get(1).action());
        assertEquals(1, audit.prune(recent.get(0).createdAt()), "only the older entry is before the cutoff");
        assertEquals(1, audit.recent(10).size());
    }

    @Test
    @DisplayName("player status upserts (last write wins) and can be counted per hash")
    void playerStatusRepository() throws Exception {
        PlayerStatusRepository repo = new PlayerStatusRepository(db);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        repo.upsert(a, "h1", "DECLINED");
        repo.upsert(a, "h1", "SUCCESSFULLY_LOADED");
        repo.upsert(b, "h1", "SUCCESSFULLY_LOADED");
        repo.upsert(UUID.randomUUID(), "other", "FAILED_DOWNLOAD");
        assertEquals("SUCCESSFULLY_LOADED", repo.find(a).orElseThrow().status());
        assertEquals(Map.of("SUCCESSFULLY_LOADED", 2), repo.countByStatus("h1"));
        assertTrue(repo.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @DisplayName("placed content ledger: namespace lookup escapes LIKE wildcards")
    void placedContentRepository() throws Exception {
        PlacedContentRepository repo = new PlacedContentRepository(db);
        repo.upsert(new PlacedContentRepository.PlacedContent(UUID.randomUUID(), "mob", "a_b:wyvern", "world", 1.5, 64, -3.25, 90f, 1));
        repo.upsert(new PlacedContentRepository.PlacedContent(UUID.randomUUID(), "furniture", "axb:chair", "world", 0, 0, 0, 0f, 1));
        repo.upsert(new PlacedContentRepository.PlacedContent(UUID.randomUUID(), "mob", "aether:golem", "nether", 0, 0, 0, 0f, 1));

        List<PlacedContentRepository.PlacedContent> hits = repo.findByNamespace("a_b");
        assertEquals(1, hits.size(), "'_' is a LIKE wildcard and must not match 'axb'");
        assertEquals("a_b:wyvern", hits.get(0).definitionId());
        assertEquals(1.5, hits.get(0).x());
        assertEquals(-3.25, hits.get(0).z());
        assertEquals(90f, hits.get(0).yaw());
        assertEquals(0, repo.findByNamespace("%").size(), "'%' must not match everything");
        assertEquals(3, repo.count());
        repo.delete(hits.get(0).entityUuid());
        assertEquals(2, repo.count());
    }

    @Test
    @DisplayName("transactions roll back completely when the work fails")
    void transactionRollback() throws Exception {
        assertThrows(SQLException.class, () -> db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mypack_player_pack_status (player_uuid, pack_hash, pack_status, updated_at) VALUES (?,?,?,?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, "h");
                ps.setString(3, "S");
                ps.setLong(4, 1);
                ps.executeUpdate();
            }
            throw new SQLException("boom");
        }));
        assertEquals(0, count("mypack_player_pack_status"));
    }

    @Test
    @DisplayName("a prefix with SQL metacharacters is rejected by the configuration parser, never reaches SQL")
    void prefixValidation() {
        List<String> warnings = new java.util.ArrayList<>();
        Settings s = Settings.parse(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.StringReader("database: {table-prefix: \"x; DROP TABLE y\"}")), warnings::add);
        assertEquals("mypack_", s.database().tablePrefix());
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("connection can be closed and reports its state")
    void lifecycle() {
        assertTrue(db.isConnected());
        db.close();
        assertFalse(db.isConnected());
        assertThrows(IllegalStateException.class, () -> db.dataSource());
    }

    @SuppressWarnings("unused")
    private static void unused(Connection c) {
        // keeps the java.sql.Connection import honest for IDEs that strip unused imports in tests
    }
}
