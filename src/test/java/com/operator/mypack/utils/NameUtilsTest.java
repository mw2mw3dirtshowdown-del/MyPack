package com.operator.mypack.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameUtilsTest {

    @Test
    @DisplayName("safe() lower-cases and maps everything outside [a-z0-9_-] to '_'")
    void safeMapsUnsafeCharacters() {
        assertEquals("left_arm", NameUtils.safe("Left Arm"));
        assertEquals("wing_l_01", NameUtils.safe("Wing.L/01"));
        assertEquals("a-b_c", NameUtils.safe("A-B_C"));
        assertEquals("_", NameUtils.safe(""));
        assertEquals("_", NameUtils.safe(null));
        assertEquals("__", NameUtils.safe("ÄÖ")); // non-ASCII is replaced, never passed through
    }

    @Test
    @DisplayName("cube model key is <model>_<bone>_<cube>, lower-case and resource-location safe")
    void cubeKey() {
        String key = NameUtils.cubeModelPath("Wyvern", "Head Top", "c0");
        assertEquals("wyvern_head_top_c0", key);
        assertEquals(key.toLowerCase(), key);
        assertTrue(NameUtils.isValidPath(key));
    }

    @Test
    @DisplayName("namespace and path validation follow Minecraft's resource location rules")
    void validation() {
        assertTrue(NameUtils.isValidNamespace("aether"));
        assertTrue(NameUtils.isValidNamespace("my-pack_1.0"));
        assertFalse(NameUtils.isValidNamespace("Aether"));
        assertFalse(NameUtils.isValidNamespace("a b"));
        assertFalse(NameUtils.isValidNamespace(""));
        assertTrue(NameUtils.isValidPath("items/blaster"));
        assertFalse(NameUtils.isValidPath("Items/Blaster"));
        assertFalse(NameUtils.isValidPath("a:b"));
    }

    @Test
    @DisplayName("slug collapses and trims separators and falls back when nothing is left")
    void slug() {
        assertEquals("my_cool_pack", NameUtils.slug("  My   Cool Pack!! ", "x"));
        assertEquals("x", NameUtils.slug("!!!", "x"));
        assertEquals("a_b", NameUtils.slug("a___b", "x"));
    }

    @Test
    @DisplayName("file name helpers work on '/' separated paths")
    void fileNames() {
        assertEquals("blaster.png", NameUtils.fileName("textures/items/blaster.png"));
        assertEquals("textures/items/blaster", NameUtils.stripExtension("textures/items/blaster.png"));
        assertEquals("dir.v2/file", NameUtils.stripExtension("dir.v2/file"));
    }
}
