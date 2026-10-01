-- MyPack schema v1: pack registry, audit trail and per-player resource pack status.
-- Every statement is idempotent (IF NOT EXISTS) so a half-applied migration can safely run again.
-- {p} is replaced with the configured table prefix. Only portable column types are used so the same
-- script runs on SQLite, MySQL and PostgreSQL.

CREATE TABLE IF NOT EXISTS {p}packs (
    pack_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    pack_ns VARCHAR(64) NOT NULL,
    pack_name VARCHAR(128) NOT NULL,
    pack_version VARCHAR(32) NOT NULL,
    source_file VARCHAR(255) NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    is_enabled INTEGER NOT NULL,
    installed_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS {p}pack_audit (
    entry_id VARCHAR(36) NOT NULL PRIMARY KEY,
    pack_uuid VARCHAR(36),
    action_type VARCHAR(32) NOT NULL,
    actor_name VARCHAR(64) NOT NULL,
    detail_text VARCHAR(512),
    created_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS {p}player_pack_status (
    player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
    pack_hash VARCHAR(40) NOT NULL,
    pack_status VARCHAR(32) NOT NULL,
    updated_at BIGINT NOT NULL
);
