package com.operator.mypack.rules;

import com.operator.mypack.config.LangManager;
import com.operator.mypack.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Keeps the shipped resources (plugin.yml, config.yml, lang/en.yml) in step with the code that reads them. */
class ResourceConsistencyTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static YamlConfiguration resource(String name) throws IOException {
        try (InputStream in = ResourceConsistencyTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name + " must be bundled in the jar");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static String allSources() throws IOException {
        assumeTrue(Files.isDirectory(SOURCES), "run from the project root to scan the sources");
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sb.append(Files.readString(file)).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ plugin.yml

    @Test
    @DisplayName("plugin.yml declares the required metadata")
    void pluginMetadata() throws IOException {
        YamlConfiguration yml = resource("plugin.yml");
        assertEquals("MyPack", yml.getString("name"));
        assertEquals("com.operator.mypack.MyPack", yml.getString("main"));
        assertEquals("1.21", yml.getString("api-version"));
        assertTrue(yml.getBoolean("folia-supported"));
        assertEquals("${version}", yml.getString("version"), "the version is substituted by processResources");
        assertNotNull(yml.getString("description"));
    }

    @Test
    @DisplayName("every permission checked in the code is declared in plugin.yml, and every declared permission is used")
    void permissionsMatch() throws IOException {
        String src = allSources();
        // only literals on lines that actually check a permission (not e.g. the default "mypack.db" file name)
        Set<String> used = new TreeSet<>();
        for (String line : src.split("\\R")) {
            if (!line.contains("hasPermission(") && !line.contains("node(\"")) {
                continue;
            }
            Matcher m = Pattern.compile("\"(mypack\\.[a-z.]+)\"").matcher(line);
            while (m.find()) {
                used.add(m.group(1));
            }
        }
        // Raw SnakeYAML, like Bukkit's own plugin.yml loader: YamlConfiguration would split "mypack.use" at the dot.
        Map<String, Object> permissions = rawPermissions();
        Set<String> declared = new TreeSet<>(permissions.keySet());
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(declared);
        assertTrue(missing.isEmpty(), "permissions used in code but not declared: " + missing);
        Set<String> unused = new TreeSet<>(declared);
        unused.removeAll(used);
        unused.remove("mypack.*");
        assertTrue(unused.isEmpty(), "permissions declared but never checked: " + unused);
        @SuppressWarnings("unchecked")
        Map<String, Object> wildcard = (Map<String, Object>) permissions.get("mypack.*");
        @SuppressWarnings("unchecked")
        Map<String, Object> children = (Map<String, Object>) wildcard.get("children");
        Set<String> notChildren = new TreeSet<>(declared);
        notChildren.remove("mypack.*");
        notChildren.removeAll(children.keySet());
        assertTrue(notChildren.isEmpty(), "mypack.* must include every permission: " + notChildren);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawPermissions() throws IOException {
        try (InputStream in = ResourceConsistencyTest.class.getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in);
            Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(in);
            return (Map<String, Object>) root.get("permissions");
        }
    }

    // ------------------------------------------------------------------ config.yml

    @Test
    @DisplayName("the shipped config.yml yields exactly the built-in defaults and no warnings")
    void configEqualsDefaults() throws IOException {
        List<String> warnings = new ArrayList<>();
        Settings shipped = Settings.parse(resource("config.yml"), warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(Settings.defaults(), shipped, "config.yml and the defaults in Settings must not drift apart");
    }

    @Test
    @DisplayName("config.yml documents every key that Settings reads")
    void configCoversEveryKey() throws IOException {
        String settingsSource = Files.readString(Path.of("src/main/java/com/operator/mypack/config/Settings.java"));
        YamlConfiguration cfg = resource("config.yml");
        Matcher m = Pattern.compile("section\\((\\w+), \"([a-z\\-]+)\"\\)").matcher(settingsSource);
        assertTrue(m.find(), "sanity: the section reads were found");
        for (String section : List.of("database", "database.pool", "packs", "packs.limits", "packs.security", "resource-pack",
                "resource-pack.supported-formats", "resource-pack.http", "models", "furniture")) {
            assertNotNull(cfg.getConfigurationSection(section), "config.yml is missing the section '" + section + "'");
        }
        for (String key : List.of("language", "database.type", "database.sqlite-file", "database.table-prefix", "packs.directory",
                "packs.auto-install", "packs.auto-enable", "packs.discover-recipes", "packs.security.allow-commands",
                "resource-pack.enabled", "resource-pack.push-on-join", "resource-pack.push-delay-ticks", "resource-pack.force",
                "resource-pack.pack-format", "resource-pack.external-url", "resource-pack.http.port", "resource-pack.http.public-host",
                "models.update-interval-ticks", "models.max-instances", "furniture.max-per-chunk")) {
            assertTrue(cfg.contains(key), "config.yml is missing '" + key + "'");
        }
    }

    // ------------------------------------------------------------------ lang/en.yml

    private static Set<String> languageKeysUsedInCode(String src) {
        Set<String> keys = new TreeSet<>();
        Matcher m = Pattern.compile("\"((?:command|gui|furniture|resourcepack)\\.[a-z0-9_.\\-]+)\"").matcher(src);
        while (m.find()) {
            String key = m.group(1);
            if (key.endsWith(".")) {
                continue; // dynamic prefix, covered separately
            }
            keys.add(key);
        }
        // false positives: config option names that look like language keys
        keys.removeAll(Set.of("furniture.interact-cooldown-ticks", "furniture.max-per-chunk", "resourcepack.sha1", "resourcepack.zip"));
        return keys;
    }

    @Test
    @DisplayName("every language key used in code exists in lang/en.yml and no key is unused")
    void languageKeysMatch() throws IOException {
        String src = allSources();
        YamlConfiguration yml = resource("lang/en.yml");
        Set<String> defined = new TreeSet<>();
        for (String key : yml.getKeys(true)) {
            Object value = yml.get(key);
            if (value instanceof String || value instanceof List) {
                defined.add(key);
            }
        }
        Set<String> used = languageKeysUsedInCode(src);
        for (String state : List.of("loaded", "disabled", "rejected", "invalid", "missing", "not_installed")) {
            used.add("command.list.entry." + state); // built dynamically from PackService.State
        }
        used.add("prefix");

        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(defined);
        assertTrue(missing.isEmpty(), "language keys used in code but missing from en.yml: " + missing);
        Set<String> unused = new TreeSet<>(defined);
        unused.removeAll(used);
        assertTrue(unused.isEmpty(), "language keys defined but never used: " + unused);
    }

    @Test
    @DisplayName("every message is valid MiniMessage without legacy colour codes")
    void messagesAreMiniMessage() throws IOException {
        YamlConfiguration yml = resource("lang/en.yml");
        MiniMessage mm = MiniMessage.miniMessage();
        for (String key : yml.getKeys(true)) {
            Object value = yml.get(key);
            List<String> lines = value instanceof String s ? List.of(s) : value instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
            for (String line : lines) {
                assertFalse(line.contains("\u00A7"), key + " contains a section sign");
                assertFalse(Pattern.compile("&[0-9a-fk-or]").matcher(line).find(), key + " uses legacy &-codes: " + line);
                Component parsed = mm.deserialize(line); // throws on legacy codes
                assertNotNull(parsed, key);
            }
        }
    }

    @Test
    @DisplayName("the bundled language file loads through LangManager and resolves its prefix")
    void langManagerLoadsBundledFile() throws IOException {
        LangManager lang = new LangManager(java.util.logging.Logger.getAnonymousLogger());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("lang/en.yml")) {
            lang.load(new InputStreamReader(in, StandardCharsets.UTF_8), null);
        }
        assertTrue(lang.has("command.reload.done"));
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(lang.get("furniture.picked-up"));
        assertTrue(plain.startsWith("MyPack"), plain);
        assertEquals(13, lang.getList("command.help").size());
    }
}
