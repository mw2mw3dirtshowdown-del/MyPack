package com.operator.mypack.content;

import com.operator.mypack.content.item.ItemAction;
import com.operator.mypack.content.item.ItemDefinition;
import com.operator.mypack.content.item.ItemDefinition.Trigger;
import com.operator.mypack.content.item.ItemParser;
import com.operator.mypack.pack.Issues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.operator.mypack.content.TestJson.ctx;
import static com.operator.mypack.content.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemParserTest {

    private static final String FULL = """
            {
              "format_version": "1.21.0",
              "minecraft:item": {
                "description": {"identifier": "aether:aether_blaster", "category": "equipment"},
                "components": {
                  "minecraft:display_name": {"value": "<gradient:aqua:blue>Aether Blaster"},
                  "minecraft:icon": {"textures": {"default": "textures/items/aether_blaster.png"}},
                  "minecraft:max_stack_size": 1,
                  "minecraft:durability": {"max_durability": 500},
                  "minecraft:glint": true,
                  "minecraft:hand_equipped": {"value": true},
                  "mypack:base_material": "diamond_sword",
                  "mypack:rarity": "epic",
                  "mypack:lore": ["<gray>Fires a bolt", "<gray>of pure sky"],
                  "mypack:attributes": [
                    {"attribute": "minecraft:generic.attack_damage", "amount": 9.5, "operation": "add_value", "slot": "mainhand"}
                  ],
                  "mypack:enchantments": {"minecraft:sharpness": 3, "unbreaking": 2},
                  "mypack:flags": ["hide_attributes"],
                  "mypack:actions": {
                    "cooldown_ticks": 40,
                    "on_right_click": [
                      {"type": "sound", "sound": "aether:blaster.fire", "volume": 1.5, "pitch": 1.2},
                      {"type": "damage_ray", "range": 30, "damage": 8, "trail_particle": "FLAME"},
                      {"type": "durability", "amount": 2}
                    ]
                  }
                }
              }
            }
            """;

    @Test
    @DisplayName("parses every supported component of a complete item")
    void parsesFullItem() {
        Issues issues = new Issues();
        ItemDefinition item = ItemParser.parse(obj(FULL), ctx(issues));
        assertNotNull(item);
        assertEquals("aether:aether_blaster", item.id().full());
        assertEquals("<gradient:aqua:blue>Aether Blaster", item.displayName());
        assertEquals("aether_blaster", item.iconTexture(), "textures/items/ prefix and .png are stripped");
        assertEquals("DIAMOND_SWORD", item.baseMaterial());
        assertEquals(1, item.maxStackSize());
        assertEquals(500, item.maxDurability());
        assertEquals(Boolean.TRUE, item.glint());
        assertTrue(item.handEquipped());
        assertEquals("EPIC", item.rarity());
        assertEquals(2, item.lore().size());
        assertEquals("attack_damage", item.attributes().get(0).attribute(), "namespace and 'generic.' are normalised away");
        assertEquals(9.5, item.attributes().get(0).amount());
        assertEquals(3, item.enchantments().get("sharpness"));
        assertEquals(java.util.List.of("HIDE_ATTRIBUTES"), item.flags());
        assertEquals(40, item.actions().cooldownTicks());
        var script = item.actions().forTrigger(Trigger.RIGHT_CLICK);
        assertEquals(3, script.size());
        ItemAction.Sound sound = assertInstanceOf(ItemAction.Sound.class, script.get(0));
        assertEquals(1.5f, sound.volume());
        ItemAction.DamageRay ray = assertInstanceOf(ItemAction.DamageRay.class, script.get(1));
        assertEquals(30.0, ray.range());
        assertEquals("FLAME", ray.trailParticle());
        assertTrue(item.actions().forTrigger(Trigger.LEFT_CLICK).isEmpty());
        assertTrue(issues.isEmpty(), issues.all().toString());
    }

    @Test
    @DisplayName("a minimal item gets sensible defaults")
    void minimalItem() {
        ItemDefinition item = ItemParser.parse(obj("""
                {"minecraft:item": {"description": {"identifier": "aether:sky_crystal_shard"}}}
                """), ctx(new Issues()));
        assertNotNull(item);
        assertEquals("Sky Crystal Shard", item.displayName());
        assertEquals("PAPER", item.baseMaterial());
        assertNull(item.iconTexture());
        assertNull(item.glint());
        assertEquals(0, item.maxStackSize());
        assertTrue(item.actions().isEmpty());
    }

    @Test
    @DisplayName("bad identifiers are rejected with an error and no definition")
    void rejectsBadIdentifiers() {
        for (String id : new String[]{"\"other:thing\"", "\"Aether:Upper\"", "\"\"", "\"aether:has space\""}) {
            Issues issues = new Issues();
            ItemDefinition item = ItemParser.parse(obj("{\"minecraft:item\":{\"description\":{\"identifier\":" + id + "}}}"), ctx(issues));
            assertNull(item, id);
            assertTrue(issues.hasErrors(), id);
        }
        Issues issues = new Issues();
        assertNull(ItemParser.parse(obj("{}"), ctx(issues)));
        assertTrue(issues.hasErrors());
    }

    @Test
    @DisplayName("bare namespace-less identifiers belong to the pack namespace")
    void bareIdentifiers() {
        ItemDefinition item = ItemParser.parse(obj("{\"minecraft:item\":{\"description\":{\"identifier\":\"plain\"}}}"), ctx(new Issues()));
        assertNotNull(item);
        assertEquals("aether:plain", item.id().full());
    }

    @Test
    @DisplayName("unsupported components, out-of-range numbers and unknown actions only produce warnings")
    void warnsButContinues() {
        Issues issues = new Issues();
        ItemDefinition item = ItemParser.parse(obj("""
                {"minecraft:item": {"description": {"identifier": "aether:x"}, "components": {
                  "minecraft:fancy_thing": true,
                  "minecraft:max_stack_size": 500,
                  "mypack:rarity": "legendary",
                  "mypack:base_material": "not a material!",
                  "mypack:actions": {"cooldown_ticks": -4, "on_right_click": [
                     {"type": "teleport_everyone"},
                     {"type": "sound"},
                     {"type": "command", "command": "/say hi"}
                  ]}
                }}}
                """), ctx(issues));
        assertNotNull(item);
        assertFalse(issues.hasErrors());
        assertEquals(99, item.maxStackSize());
        assertNull(item.rarity());
        assertEquals("PAPER", item.baseMaterial());
        assertEquals(0, item.actions().cooldownTicks());
        var script = item.actions().forTrigger(Trigger.RIGHT_CLICK);
        assertEquals(1, script.size(), "unknown action and sound without a sound are dropped");
        ItemAction.Command command = assertInstanceOf(ItemAction.Command.class, script.get(0));
        assertEquals("say hi", command.command(), "leading slash is stripped");
        assertEquals("player", command.executor(), "commands default to the less privileged executor");
        assertTrue(issues.count(Issues.Level.WARN) >= 5, issues.all().toString());
    }

    @Test
    @DisplayName("legacy section signs in display names do not break parsing (they are handled at render time)")
    void legacyCodesAreJustText() {
        ItemDefinition item = ItemParser.parse(obj("""
                {"minecraft:item": {"description": {"identifier": "aether:x"}, "components": {"minecraft:display_name": "\\u00a7cRed"}}}
                """), ctx(new Issues()));
        assertNotNull(item);
        assertEquals("\u00a7cRed", item.displayName());
    }
}
