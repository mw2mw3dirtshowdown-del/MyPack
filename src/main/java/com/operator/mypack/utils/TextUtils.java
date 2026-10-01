package com.operator.mypack.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * MiniMessage helpers. All player-facing text in MyPack is MiniMessage; legacy section/ampersand codes are never
 * produced or parsed.
 */
public final class TextUtils {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private TextUtils() {
    }

    public static MiniMessage miniMessage() {
        return MM;
    }

    /**
     * Parses MiniMessage. Text that comes from packs or language files is untrusted input, so this never throws:
     * MiniMessage rejects strings containing legacy section signs, in which case the text is shown literally with the
     * section signs removed (legacy colour codes are never rendered).
     */
    public static Component mm(String miniMessage, TagResolver... resolvers) {
        String text = miniMessage == null ? "" : miniMessage;
        try {
            return MM.deserialize(text, resolvers);
        } catch (RuntimeException e) {
            return Component.text(text.replace("\u00A7", ""));
        }
    }

    /**
     * Item names and lore render italic by default in the client; this turns italics off unless the author asked
     * for them explicitly with {@code <italic>}.
     */
    public static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component mmNoItalic(String miniMessage, TagResolver... resolvers) {
        return noItalic(mm(miniMessage, resolvers));
    }

    public static List<Component> mmNoItalic(List<String> lines, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(mmNoItalic(line, resolvers));
        }
        return out;
    }

    public static String plain(Component component) {
        return PLAIN.serialize(component);
    }

    /** Escapes MiniMessage tags so arbitrary text (player names, file names, pack metadata) is rendered literally. */
    public static String escape(String raw) {
        return MM.escapeTags(raw == null ? "" : raw);
    }
}
