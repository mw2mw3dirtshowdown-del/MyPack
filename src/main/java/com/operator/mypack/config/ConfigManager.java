package com.operator.mypack.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

/** Loads {@code config.yml} into an immutable {@link Settings} snapshot and swaps it atomically on reload. */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private volatile Settings settings = Settings.defaults();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * (Re)reads the configuration from disk. Missing keys fall back to the defaults bundled in the jar, invalid values
     * are corrected with a warning in the console.
     */
    public Settings load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        Settings parsed = Settings.parse(plugin.getConfig(),
                message -> plugin.getLogger().warning("config.yml: " + message));
        this.settings = parsed;
        return parsed;
    }

    public Settings settings() {
        return settings;
    }

    public Path dataPath() {
        return plugin.getDataFolder().toPath();
    }
}
