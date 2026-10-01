package com.operator.mypack.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    private static Settings parse(String yaml, List<String> warnings) {
        return Settings.parse(YamlConfiguration.loadConfiguration(new StringReader(yaml)), warnings::add);
    }

    @Test
    @DisplayName("an empty file yields the documented defaults without warnings")
    void defaults() {
        List<String> warnings = new ArrayList<>();
        Settings s = parse("", warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals("en", s.language());
        assertEquals(Settings.DatabaseType.SQLITE, s.database().type());
        assertEquals("mypack_", s.database().tablePrefix());
        assertEquals(46, s.resourcePack().packFormat());
        assertEquals(34, s.resourcePack().supportedMin());
        assertEquals(46, s.resourcePack().supportedMax());
        assertEquals(8123, s.resourcePack().http().port());
        assertTrue(s.resourcePack().force());
        assertFalse(s.packs().allowCommands(), "pack commands must be opt-in");
        assertEquals(256L * 1024 * 1024, s.packs().limits().maxArchiveBytes());
    }

    @Test
    @DisplayName("invalid values are corrected and reported instead of failing")
    void validatesAndClamps() {
        List<String> warnings = new ArrayList<>();
        Settings s = parse("""
                language: "not a language!"
                database:
                  type: oracle
                  table-prefix: "bad prefix; DROP TABLE"
                  port: 99999
                resource-pack:
                  push-delay-ticks: -5
                  supported-formats: {min: 60, max: 40}
                  http:
                    public-scheme: ftp
                    port: 0
                packs:
                  directory: "../outside"
                """, warnings);
        assertEquals("en", s.language());
        assertEquals(Settings.DatabaseType.SQLITE, s.database().type());
        assertEquals("mypack_", s.database().tablePrefix());
        assertEquals(65535, s.database().port());
        assertEquals(1, s.resourcePack().pushDelayTicks());
        assertEquals(40, s.resourcePack().supportedMin());
        assertEquals(60, s.resourcePack().supportedMax());
        assertEquals("http", s.resourcePack().http().publicScheme());
        assertEquals(1, s.resourcePack().http().port());
        assertEquals("packs", s.packs().directory(), "directory must stay inside the plugin folder");
        assertTrue(warnings.size() >= 8, "every correction is reported: " + warnings);
    }

    @Test
    @DisplayName("database type aliases are understood and the default port follows the type")
    void databaseTypes() {
        List<String> warnings = new ArrayList<>();
        assertEquals(Settings.DatabaseType.POSTGRESQL, parse("database: {type: postgres}", warnings).database().type());
        assertEquals(5432, parse("database: {type: postgresql}", warnings).database().port());
        assertEquals(Settings.DatabaseType.MYSQL, parse("database: {type: MariaDB}", warnings).database().type());
        assertEquals(3306, parse("database: {type: mysql}", warnings).database().port());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }
}
