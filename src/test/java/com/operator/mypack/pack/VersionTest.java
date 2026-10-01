package com.operator.mypack.pack;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    @Test
    @DisplayName("parses 1, 2 and 3 component strings and ignores a suffix")
    void parsesStrings() {
        assertEquals(new Version(1, 0, 0), Version.parse("1"));
        assertEquals(new Version(1, 21, 0), Version.parse("1.21"));
        assertEquals(new Version(1, 21, 4), Version.parse("1.21.4"));
        assertEquals(new Version(2, 0, 1), Version.parse("2.0.1-beta+build5"));
    }

    @Test
    @DisplayName("rejects garbage and negative numbers")
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Version.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Version.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.2.3.4"));
        assertFalse(Version.tryParse(null).isPresent());
        assertThrows(IllegalArgumentException.class, () -> new Version(-1, 0, 0));
    }

    @Test
    @DisplayName("reads the [major, minor, patch] array form used by manifests")
    void readsJsonArrays() {
        assertEquals(new Version(1, 2, 3), Version.fromJson(JsonParser.parseString("[1,2,3]")).orElseThrow());
        assertEquals(new Version(4, 0, 0), Version.fromJson(JsonParser.parseString("[4]")).orElseThrow());
        assertEquals(new Version(1, 0, 5), Version.fromJson(JsonParser.parseString("\"1.0.5\"")).orElseThrow());
        assertFalse(Version.fromJson(JsonParser.parseString("[]")).isPresent());
        assertFalse(Version.fromJson(JsonParser.parseString("[1,2,3,4]")).isPresent());
        assertFalse(Version.fromJson(JsonParser.parseString("[1,-2,3]")).isPresent());
        assertFalse(Version.fromJson(JsonParser.parseString("[\"a\"]")).isPresent());
        assertFalse(Version.fromJson(JsonParser.parseString("{}")).isPresent());
    }

    @Test
    @DisplayName("orders versions numerically, not lexically")
    void ordersNumerically() {
        assertTrue(Version.parse("1.10.0").compareTo(Version.parse("1.9.0")) > 0);
        assertTrue(Version.parse("1.2.3").atLeast(Version.parse("1.2.3")));
        assertTrue(Version.parse("2.0.0").atLeast(Version.parse("1.99.99")));
        assertFalse(Version.parse("1.2.2").atLeast(Version.parse("1.2.3")));
        assertEquals("1.21.4", new Version(1, 21, 4).toString());
    }
}
