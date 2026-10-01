package com.operator.mypack.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Immutable snapshot of {@code config.yml}. Parsing is defensive: every value is validated and clamped and each
 * correction is reported through the {@code warn} callback, so a typo in the file never prevents the plugin from
 * starting.
 */
public record Settings(
        String language,
        boolean debug,
        Database database,
        Packs packs,
        ResourcePack resourcePack,
        Models models,
        Furniture furniture) {

    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,24}");
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})?");

    public enum DatabaseType {
        SQLITE, MYSQL, POSTGRESQL
    }

    public record Database(
            DatabaseType type,
            String sqliteFile,
            String host,
            int port,
            String name,
            String username,
            String password,
            boolean useSsl,
            String tablePrefix,
            int maxPoolSize,
            int minIdle,
            long connectionTimeoutMs,
            long maxLifetimeMs) {
    }

    public record Limits(long maxArchiveBytes, long maxExtractedBytes, long maxEntryBytes, int maxEntries) {
    }

    public record Packs(String directory, boolean autoInstall, boolean autoEnable, boolean allowCommands,
                        boolean discoverRecipes, Limits limits) {
    }

    public record Http(
            boolean enabled,
            String bindAddress,
            int port,
            String publicHost,
            String publicScheme,
            int publicPort) {
    }

    public record ResourcePack(
            boolean enabled,
            boolean pushOnJoin,
            int pushDelayTicks,
            boolean force,
            int packFormat,
            int supportedMin,
            int supportedMax,
            String description,
            String packId,
            String externalUrl,
            Http http) {
    }

    public record Models(int updateIntervalTicks, float viewRange, boolean hideBaseEntity, int maxInstances) {
    }

    public record Furniture(boolean enabled, int maxPerChunk, int interactCooldownTicks) {
    }

    /** Settings built from an empty configuration, i.e. every built-in default. */
    public static Settings defaults() {
        return parse(new YamlConfiguration(), w -> {
        });
    }

    public static Settings parse(ConfigurationSection c, Consumer<String> warn) {
        String language = c.getString("language", "en");
        if (language == null || !LANGUAGE.matcher(language).matches()) {
            warn.accept("language '" + language + "' is not a valid language code, using 'en'");
            language = "en";
        }

        // ---- database -------------------------------------------------------------------
        ConfigurationSection d = section(c, "database");
        DatabaseType type = parseDatabaseType(d.getString("type", "sqlite"), warn);
        int defaultPort = type == DatabaseType.POSTGRESQL ? 5432 : 3306;
        String prefix = d.getString("table-prefix", "mypack_");
        if (prefix == null || !TABLE_PREFIX.matcher(prefix).matches()) {
            warn.accept("database.table-prefix must match [A-Za-z0-9_]{0,24}; using 'mypack_'");
            prefix = "mypack_";
        }
        ConfigurationSection pool = section(d, "pool");
        Database database = new Database(
                type,
                nonBlank(d.getString("sqlite-file"), "mypack.db"),
                nonBlank(d.getString("host"), "127.0.0.1"),
                clamp(d.getInt("port", defaultPort), 1, 65535, "database.port", warn),
                nonBlank(d.getString("name"), "mypack"),
                nonBlank(d.getString("username"), "mypack"),
                d.getString("password", ""),
                d.getBoolean("use-ssl", false),
                prefix,
                clamp(pool.getInt("max-size", 6), 1, 64, "database.pool.max-size", warn),
                clamp(pool.getInt("min-idle", 1), 0, 64, "database.pool.min-idle", warn),
                clampLong(pool.getLong("connection-timeout-ms", 10_000L), 1_000L, 120_000L,
                        "database.pool.connection-timeout-ms", warn),
                clampLong(pool.getLong("max-lifetime-ms", 1_800_000L), 60_000L, 7_200_000L,
                        "database.pool.max-lifetime-ms", warn));

        // ---- packs ----------------------------------------------------------------------
        ConfigurationSection p = section(c, "packs");
        ConfigurationSection lim = section(p, "limits");
        Limits limits = new Limits(
                mb(clampLong(lim.getLong("max-archive-mb", 256L), 1L, 4096L, "packs.limits.max-archive-mb", warn)),
                mb(clampLong(lim.getLong("max-extracted-mb", 512L), 1L, 8192L, "packs.limits.max-extracted-mb", warn)),
                mb(clampLong(lim.getLong("max-entry-mb", 64L), 1L, 1024L, "packs.limits.max-entry-mb", warn)),
                clamp(lim.getInt("max-entries", 20_000), 10, 500_000, "packs.limits.max-entries", warn));
        String directory = nonBlank(p.getString("directory"), "packs");
        if (directory.contains("..") || directory.startsWith("/") || directory.startsWith("\\") || directory.contains(":")) {
            warn.accept("packs.directory must be a relative folder inside the plugin folder; using 'packs'");
            directory = "packs";
        }
        Packs packs = new Packs(
                directory,
                p.getBoolean("auto-install", true),
                p.getBoolean("auto-enable", true),
                section(p, "security").getBoolean("allow-commands", false),
                p.getBoolean("discover-recipes", true),
                limits);

        // ---- resource pack --------------------------------------------------------------
        ConfigurationSection r = section(c, "resource-pack");
        ConfigurationSection supported = section(r, "supported-formats");
        ConfigurationSection h = section(r, "http");
        int packFormat = clamp(r.getInt("pack-format", 46), 1, 1000, "resource-pack.pack-format", warn);
        int supportedMin = clamp(supported.getInt("min", 34), 1, 1000, "resource-pack.supported-formats.min", warn);
        int supportedMax = clamp(supported.getInt("max", 46), 1, 1000, "resource-pack.supported-formats.max", warn);
        if (supportedMin > supportedMax) {
            warn.accept("resource-pack.supported-formats.min is greater than max; swapping them");
            int tmp = supportedMin;
            supportedMin = supportedMax;
            supportedMax = tmp;
        }
        String scheme = nonBlank(h.getString("public-scheme"), "http").toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            warn.accept("resource-pack.http.public-scheme must be http or https; using http");
            scheme = "http";
        }
        Http http = new Http(
                h.getBoolean("enabled", true),
                nonBlank(h.getString("bind-address"), "0.0.0.0"),
                clamp(h.getInt("port", 8123), 1, 65535, "resource-pack.http.port", warn),
                h.getString("public-host", "").trim(),
                scheme,
                clamp(h.getInt("public-port", 0), 0, 65535, "resource-pack.http.public-port", warn));
        ResourcePack resourcePack = new ResourcePack(
                r.getBoolean("enabled", true),
                r.getBoolean("push-on-join", true),
                clamp(r.getInt("push-delay-ticks", 20), 1, 1200, "resource-pack.push-delay-ticks", warn),
                r.getBoolean("force", true),
                packFormat,
                supportedMin,
                supportedMax,
                nonBlank(r.getString("description"), "MyPack content"),
                r.getString("pack-id", "").trim(),
                r.getString("external-url", "").trim(),
                http);

        // ---- models / furniture ---------------------------------------------------------
        ConfigurationSection m = section(c, "models");
        Models models = new Models(
                clamp(m.getInt("update-interval-ticks", 1), 1, 20, "models.update-interval-ticks", warn),
                (float) Math.max(0.1D, Math.min(10.0D, m.getDouble("view-range", 1.0D))),
                m.getBoolean("hide-base-entity", true),
                clamp(m.getInt("max-instances", 4000), 1, 100_000, "models.max-instances", warn));

        ConfigurationSection f = section(c, "furniture");
        Furniture furniture = new Furniture(
                f.getBoolean("enabled", true),
                clamp(f.getInt("max-per-chunk", 64), 1, 4096, "furniture.max-per-chunk", warn),
                clamp(f.getInt("interact-cooldown-ticks", 5), 0, 200, "furniture.interact-cooldown-ticks", warn));

        return new Settings(language, c.getBoolean("debug", false), database, packs, resourcePack, models, furniture);
    }

    // ------------------------------------------------------------------ helpers

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        ConfigurationSection s = parent.getConfigurationSection(key);
        return s != null ? s : new YamlConfiguration();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static long mb(long megabytes) {
        return megabytes * 1024L * 1024L;
    }

    private static int clamp(int value, int min, int max, String key, Consumer<String> warn) {
        if (value < min || value > max) {
            int fixed = Math.max(min, Math.min(max, value));
            warn.accept(key + " = " + value + " is outside " + min + ".." + max + "; using " + fixed);
            return fixed;
        }
        return value;
    }

    private static long clampLong(long value, long min, long max, String key, Consumer<String> warn) {
        if (value < min || value > max) {
            long fixed = Math.max(min, Math.min(max, value));
            warn.accept(key + " = " + value + " is outside " + min + ".." + max + "; using " + fixed);
            return fixed;
        }
        return value;
    }

    private static DatabaseType parseDatabaseType(String raw, Consumer<String> warn) {
        String value = raw == null ? "sqlite" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "sqlite", "sqlite3" -> DatabaseType.SQLITE;
            case "mysql", "mariadb" -> DatabaseType.MYSQL;
            case "postgresql", "postgres", "pgsql", "pg" -> DatabaseType.POSTGRESQL;
            default -> {
                warn.accept("database.type = '" + raw + "' is not one of sqlite, mysql, postgresql; using sqlite");
                yield DatabaseType.SQLITE;
            }
        };
    }
}
