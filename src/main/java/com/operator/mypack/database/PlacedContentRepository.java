package com.operator.mypack.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ledger of spawned mobs and placed furniture so they can be located and cleaned up later. */
public final class PlacedContentRepository {

    public static final String KIND_MOB = "mob";
    public static final String KIND_FURNITURE = "furniture";

    /** One tracked entity. */
    public record PlacedContent(
            UUID entityUuid,
            String kind,
            String definitionId,
            String world,
            double x,
            double y,
            double z,
            float yaw,
            long createdAt) {
    }

    private static final List<String> KEY = List.of("entity_uuid");
    private static final List<String> VALUES = List.of("content_kind", "definition_id", "world_name", "pos_x", "pos_y",
            "pos_z", "yaw_degrees", "created_at");

    private final DatabaseManager db;

    public PlacedContentRepository(DatabaseManager db) {
        this.db = db;
    }

    public void upsert(PlacedContent content) throws SQLException {
        String sql = db.dialect().upsert(db.table("placed_content"), KEY, VALUES);
        db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, content.entityUuid().toString());
                ps.setString(2, content.kind());
                ps.setString(3, content.definitionId());
                ps.setString(4, content.world());
                ps.setDouble(5, content.x());
                ps.setDouble(6, content.y());
                ps.setDouble(7, content.z());
                ps.setFloat(8, content.yaw());
                ps.setLong(9, content.createdAt());
                return ps.executeUpdate();
            }
        });
    }

    public void delete(UUID entityUuid) throws SQLException {
        String sql = "DELETE FROM " + db.table("placed_content") + " WHERE entity_uuid = ?";
        db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, entityUuid.toString());
                return ps.executeUpdate();
            }
        });
    }

    /** All entries whose definition id starts with {@code "<namespace>:"}. */
    public List<PlacedContent> findByNamespace(String namespace) throws SQLException {
        String sql = "SELECT entity_uuid, content_kind, definition_id, world_name, pos_x, pos_y, pos_z, yaw_degrees, "
                + "created_at FROM " + db.table("placed_content") + " WHERE definition_id LIKE ? ESCAPE '!'";
        return db.run(connection -> {
            List<PlacedContent> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, escapeLike(namespace) + ":%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new PlacedContent(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getFloat(8),
                                rs.getLong(9)));
                    }
                }
            }
            return out;
        });
    }

    public int count() throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + db.table("placed_content");
        return db.run(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private static String escapeLike(String raw) {
        // '!' is the LIKE escape character: a backslash would need different quoting on MySQL.
        return raw.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
