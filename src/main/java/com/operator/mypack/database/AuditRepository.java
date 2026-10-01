package com.operator.mypack.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Append-only audit trail of pack installs, removals and reloads. Blocking JDBC - call from an async task. */
public final class AuditRepository {

    /** One audit record. {@code packUuid} may be {@code null} for server-wide actions. */
    public record AuditEntry(UUID entryId, UUID packUuid, String action, String actor, String detail, long createdAt) {
    }

    private final DatabaseManager db;

    public AuditRepository(DatabaseManager db) {
        this.db = db;
    }

    public void log(UUID packUuid, String action, String actor, String detail) throws SQLException {
        String sql = "INSERT INTO " + db.table("pack_audit")
                + " (entry_id, pack_uuid, action_type, actor_name, detail_text, created_at) VALUES (?, ?, ?, ?, ?, ?)";
        db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, packUuid == null ? null : packUuid.toString());
                ps.setString(3, truncate(action, 32));
                ps.setString(4, truncate(actor, 64));
                ps.setString(5, detail == null ? null : truncate(detail, 512));
                ps.setLong(6, System.currentTimeMillis());
                return ps.executeUpdate();
            }
        });
    }

    public List<AuditEntry> recent(int limit) throws SQLException {
        String sql = "SELECT entry_id, pack_uuid, action_type, actor_name, detail_text, created_at FROM "
                + db.table("pack_audit") + " ORDER BY created_at DESC LIMIT ?";
        return db.run(connection -> {
            List<AuditEntry> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, Math.max(1, Math.min(limit, 500)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String pack = rs.getString(2);
                        out.add(new AuditEntry(UUID.fromString(rs.getString(1)), pack == null ? null : UUID.fromString(pack),
                                rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)));
                    }
                }
            }
            return out;
        });
    }

    /** Deletes entries older than {@code cutoffMillis}; returns the number of removed rows. */
    public int prune(long cutoffMillis) throws SQLException {
        String sql = "DELETE FROM " + db.table("pack_audit") + " WHERE created_at < ?";
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, cutoffMillis);
                return ps.executeUpdate();
            }
        });
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
