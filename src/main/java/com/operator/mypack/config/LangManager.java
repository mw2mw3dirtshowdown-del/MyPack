package com.operator.mypack.config;

import com.operator.mypack.utils.TextUtils;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Message catalogue backed by {@code lang/<language>.yml}. Every value is a MiniMessage string; the {@code prefix}
 * entry is available to all messages as the {@code <prefix>} tag. Keys that are missing in the user's file fall back
 * to the bundled English file, so updating the plugin never produces blank messages.
 */
public final class LangManager {

    private final Logger logger;
    private volatile Map<String, String> messages = Map.of();
    private volatile Map<String, List<String>> lists = Map.of();

    public LangManager(Logger logger) {
        this.logger = logger;
    }

    /** Loads {@code lang/<language>.yml} from the data folder, copying the bundled file there on first use. */
    public void load(JavaPlugin plugin, String language) {
        String fileName = "lang/" + language + ".yml";
        File target = new File(plugin.getDataFolder(), fileName);
        if (!target.exists()) {
            if (plugin.getResource(fileName) != null) {
                plugin.saveResource(fileName, false);
            } else if (!"en".equals(language)) {
                logger.warning("Language '" + language + "' does not exist; falling back to 'en'.");
                plugin.saveResource("lang/en.yml", false);
                target = new File(plugin.getDataFolder(), "lang/en.yml");
            }
        }
        if (!target.exists()) {
            target = new File(plugin.getDataFolder(), "lang/en.yml");
            if (!target.exists()) {
                plugin.saveResource("lang/en.yml", false);
            }
        }
        try (InputStream bundled = plugin.getResource("lang/en.yml");
             Reader primary = new InputStreamReader(java.nio.file.Files.newInputStream(target.toPath()), StandardCharsets.UTF_8)) {
            Reader fallback = bundled == null ? null : new InputStreamReader(bundled, StandardCharsets.UTF_8);
            load(primary, fallback);
        } catch (IOException e) {
            logger.severe("Could not read " + target + ": " + e.getMessage());
        }
    }

    /** Pure loading entry point (also used by the unit tests): {@code fallback} may be {@code null}. */
    public void load(Reader primary, Reader fallback) {
        Map<String, String> msgs = new HashMap<>();
        Map<String, List<String>> lsts = new HashMap<>();
        if (fallback != null) {
            flatten(YamlConfiguration.loadConfiguration(fallback), msgs, lsts);
        }
        flatten(YamlConfiguration.loadConfiguration(primary), msgs, lsts);
        this.messages = Map.copyOf(msgs);
        this.lists = Map.copyOf(lsts);
    }

    private static void flatten(ConfigurationSection section, Map<String, String> msgs, Map<String, List<String>> lsts) {
        for (String key : section.getKeys(true)) {
            Object value = section.get(key);
            if (value instanceof String s) {
                msgs.put(key, s);
            } else if (value instanceof List<?> list) {
                List<String> lines = new ArrayList<>(list.size());
                for (Object o : list) {
                    lines.add(String.valueOf(o));
                }
                lsts.put(key, lines);
            }
        }
    }

    public boolean has(String key) {
        return messages.containsKey(key) || lists.containsKey(key);
    }

    /** Raw MiniMessage text of a key, or a visible marker when the key does not exist. */
    public String raw(String key) {
        String value = messages.get(key);
        if (value != null) {
            return value;
        }
        List<String> list = lists.get(key);
        if (list != null) {
            return String.join("\n", list);
        }
        return "<red>Missing language key: <yellow>" + key;
    }

    public Component get(String key, TagResolver... resolvers) {
        return TextUtils.mm(raw(key), withPrefix(resolvers));
    }

    /** Message that is used as an item name / lore line (italics off). */
    public Component getNoItalic(String key, TagResolver... resolvers) {
        return TextUtils.noItalic(get(key, resolvers));
    }

    public List<Component> getList(String key, TagResolver... resolvers) {
        List<String> lines = lists.get(key);
        if (lines == null) {
            return List.of(get(key, resolvers));
        }
        TagResolver combined = withPrefix(resolvers);
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(TextUtils.mm(line, combined));
        }
        return out;
    }

    public List<Component> getListNoItalic(String key, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>();
        for (Component c : getList(key, resolvers)) {
            out.add(TextUtils.noItalic(c));
        }
        return out;
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(get(key, resolvers));
    }

    public void sendList(Audience audience, String key, TagResolver... resolvers) {
        for (Component line : getList(key, resolvers)) {
            audience.sendMessage(line);
        }
    }

    /** Convenience for {@code <name>}-style placeholders that must be rendered literally. */
    public static TagResolver unparsed(String name, String value) {
        return Placeholder.unparsed(name, value == null ? "" : value);
    }

    public static TagResolver number(String name, Number value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    private TagResolver withPrefix(TagResolver... resolvers) {
        String prefixRaw = messages.getOrDefault("prefix", "");
        return TagResolver.builder()
                .resolver(Placeholder.parsed("prefix", prefixRaw))
                .resolvers(resolvers)
                .build();
    }
}
