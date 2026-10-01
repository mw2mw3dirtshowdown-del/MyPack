package com.operator.mypack.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Last known resource pack state of every player (hash they were sent and what the client answered). */
public final class PlayerStatusRepository {

    /** The stored row for one player. */
    public record PlayerStatus(UUID player, String packHash, String status, long updatedAt) {
    }

    private static final List<String> KEY = List.of("player_uuid");
    private static final List<String> VALUES = List.of("pack_hash", "pack_status", "updated_at");

    private final DatabaseManager db;

    public PlayerStatusRepository(DatabaseManager db) {
        this.db = db;
    }

    public void upsert(UUID player, String packHash, String status) throws SQLException {
        String sql = db.dialect().upsert(db.table("player_pack_status"), KEY, VALUES);
        db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                ps.setString(2, packHash);
                ps.setString(3, status);
                ps.setLong(4, System.currentTimeMillis());
                return ps.executeUpdate();
            }
        });
    }

    public Optional<PlayerStatus> find(UUID player) throws SQLException {
        String sql = "SELECT player_uuid, pack_hash, pack_status, updated_at FROM "
                + db.table("player_pack_status") + " WHERE player_uuid = ?";
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<PlayerStatus>empty();
                    }
                    return Optional.of(new PlayerStatus(UUID.fromString(rs.getString(1)), rs.getString(2),
                            rs.getString(3), rs.getLong(4)));
                }
            }
        });
    }

    /** Number of players per status for the given pack hash (used by {@code /mypack resourcepack status}). */
    public Map<String, Integer> countByStatus(String packHash) throws SQLException {
        String sql = "SELECT pack_status, COUNT(*) FROM " + db.table("player_pack_status")
                + " WHERE pack_hash = ? GROUP BY pack_status";
        return db.run(connection -> {
            Map<String, Integer> out = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, packHash);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getInt(2));
                    }
                }
            }
            return out;
        });
    }
}
