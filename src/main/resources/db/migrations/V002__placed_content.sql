-- MyPack schema v2: ledger of spawned custom mobs and placed furniture.
-- The ledger lets the plugin find (and clean up) content of an uninstalled pack even inside unloaded chunks.

CREATE TABLE IF NOT EXISTS {p}placed_content (
    entity_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    content_kind VARCHAR(16) NOT NULL,
    definition_id VARCHAR(128) NOT NULL,
    world_name VARCHAR(64) NOT NULL,
    pos_x DOUBLE PRECISION NOT NULL,
    pos_y DOUBLE PRECISION NOT NULL,
    pos_z DOUBLE PRECISION NOT NULL,
    yaw_degrees REAL NOT NULL,
    created_at BIGINT NOT NULL
);
