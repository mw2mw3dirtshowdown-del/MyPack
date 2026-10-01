package com.operator.mypack.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistent registry of installed packs. Blocking JDBC - call from an async task. */
public final class PackRepository {

    /** One row of the pack registry. */
    public record PackRecord(
            UUID uuid,
            String namespace,
            String name,
            String version,
            String sourceFile,
            String fingerprint,
            boolean enabled,
            long installedAt,
            long updatedAt) {
    }

    private static final List<String> KEY = List.of("pack_uuid");
    private static final List<String> VALUES = List.of("pack_ns", "pack_name", "pack_version", "source_file",
            "source_fingerprint", "is_enabled", "installed_at", "updated_at");

    private final DatabaseManager db;

    public PackRepository(DatabaseManager db) {
        this.db = db;
    }

    public List<PackRecord> findAll() throws SQLException {
        String sql = "SELECT pack_uuid, pack_ns, pack_name, pack_version, source_file, source_fingerprint, "
                + "is_enabled, installed_at, updated_at FROM " + db.table("packs") + " ORDER BY installed_at ASC";
        return db.run(connection -> {
            List<PackRecord> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        });
    }

    public Optional<PackRecord> findBySource(String sourceFile) throws SQLException {
        String sql = "SELECT pack_uuid, pack_ns, pack_name, pack_version, source_file, source_fingerprint, "
                + "is_enabled, installed_at, updated_at FROM " + db.table("packs") + " WHERE source_file = ?";
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, sourceFile);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<PackRecord>empty();
                }
            }
        });
    }

    public void upsert(PackRecord record) throws SQLException {
        String sql = db.dialect().upsert(db.table("packs"), KEY, VALUES);
        db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, record.uuid().toString());
                ps.setString(2, record.namespace());
                ps.setString(3, record.name());
                ps.setString(4, record.version());
                ps.setString(5, record.sourceFile());
                ps.setString(6, record.fingerprint());
                ps.setInt(7, record.enabled() ? 1 : 0);
                ps.setLong(8, record.installedAt());
                ps.setLong(9, record.updatedAt());
                return ps.executeUpdate();
            }
        });
    }

    public void setEnabled(UUID uuid, boolean enabled, long now) throws SQLException {
        String sql = "UPDATE " + db.table("packs") + " SET is_enabled = ?, updated_at = ? WHERE pack_uuid = ?";
        db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setLong(2, now);
                ps.setString(3, uuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    public void delete(UUID uuid) throws SQLException {
        String sql = "DELETE FROM " + db.table("packs") + " WHERE pack_uuid = ?";
        db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    private static PackRecord read(ResultSet rs) throws SQLException {
        return new PackRecord(
                UUID.fromString(rs.getString(1)),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getInt(7) != 0,
                rs.getLong(8),
                rs.getLong(9));
    }
}
