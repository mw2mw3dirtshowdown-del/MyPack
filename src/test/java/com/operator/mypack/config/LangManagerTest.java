package com.operator.mypack.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangManagerTest {

    private static final String BUNDLED = """
            prefix: "<gray>[<gold>MyPack</gold>]</gray> "
            hello: "<prefix>Hello <name>!"
            only-in-fallback: "<green>fallback value"
            overridden: "<red>english"
            lines:
              - "<gray>first"
              - "<gray>second <name>"
            """;

    private static final String USER = """
            overridden: "<blue>translated"
            """;

    private static LangManager loaded() {
        LangManager lang = new LangManager(Logger.getAnonymousLogger());
        lang.load(new StringReader(USER), new StringReader(BUNDLED));
        return lang;
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    @DisplayName("user file wins, bundled file fills the gaps")
    void fallbackMerging() {
        LangManager lang = loaded();
        assertEquals("translated", plain(lang.get("overridden")));
        assertEquals("fallback value", plain(lang.get("only-in-fallback")));
    }

    @Test
    @DisplayName("<prefix> and placeholders are resolved; placeholder text is never parsed as MiniMessage")
    void prefixAndPlaceholders() {
        LangManager lang = loaded();
        assertEquals("[MyPack] Hello Steve!", plain(lang.get("hello", Placeholder.unparsed("name", "Steve"))));
        // a hostile value must be rendered literally, not interpreted
        String rendered = plain(lang.get("hello", Placeholder.unparsed("name", "<red>Evil</red><click:run_command:'/op me'>x")));
        assertTrue(rendered.contains("<red>Evil</red>"), rendered);
        Component component = lang.get("hello", Placeholder.unparsed("name", "<click:run_command:'/op me'>x"));
        assertFalse(containsClick(component), "no click event may be injected through a placeholder value");
    }

    private static boolean containsClick(Component c) {
        if (c.clickEvent() != null) {
            return true;
        }
        for (Component child : c.children()) {
            if (containsClick(child)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("lists are returned line by line")
    void lists() {
        LangManager lang = loaded();
        List<Component> lines = lang.getList("lines", Placeholder.unparsed("name", "X"));
        assertEquals(2, lines.size());
        assertEquals("second X", plain(lines.get(1)));
    }

    @Test
    @DisplayName("missing keys render a visible marker instead of nothing")
    void missingKey() {
        LangManager lang = loaded();
        assertFalse(lang.has("nope"));
        assertTrue(plain(lang.get("nope")).contains("nope"));
    }

    @Test
    @DisplayName("item style messages have italics switched off")
    void noItalic() {
        LangManager lang = loaded();
        Component c = lang.getNoItalic("only-in-fallback");
        assertEquals(TextDecoration.State.FALSE, c.decoration(TextDecoration.ITALIC));
    }

    @Test
    @DisplayName("legacy colour codes are never rendered and never crash the parser")
    void legacyCodesAreNeverRendered() {
        LangManager lang = new LangManager(Logger.getAnonymousLogger());
        lang.load(new StringReader("legacy: \"&cRed \u00A7aGreen\"\nampersand: \"&cOnly ampersand\""), null);
        String withSection = plain(lang.get("legacy"));
        assertFalse(withSection.contains("\u00A7"), "section signs are stripped: " + withSection);
        assertEquals("&cRed aGreen", withSection);
        assertEquals("&cOnly ampersand", plain(lang.get("ampersand")), "ampersand codes are plain text");
    }
}
