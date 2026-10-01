package com.operator.mypack.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestParserTest {

    private static final String VALID = """
            {
              // comments are legal in Bedrock manifests
              "format_version": 2,
              "header": {
                "name": "Aether Arsenal",
                "description": "Sky weapons",
                "uuid": "2f0b0c83-8a6b-4e55-9f0a-6a1d6c1f2b11",
                "version": [1, 2, 3],
                "min_engine_version": [1, 21, 4]
              },
              "modules": [
                {"type": "data", "uuid": "11111111-1111-1111-1111-111111111111", "version": [1, 0, 0]},
                {"type": "resources", "uuid": "22222222-2222-2222-2222-222222222222", "version": [1, 0, 0]},
                {"type": "script", "uuid": "33333333-3333-3333-3333-333333333333", "version": [1, 0, 0]}
              ],
              "dependencies": [
                {"uuid": "44444444-4444-4444-4444-444444444444", "version": [1, 1, 0]},
                {"module_name": "@minecraft/server", "version": "1.0.0"}
              ],
              "metadata": {"namespace": "aether", "authors": ["Alice", "Bob"], "license": "MIT", "url": "https://example.com"}
            }
            """;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("parses a complete manifest including comments, modules and dependencies")
    void parsesValidManifest() throws Exception {
        Issues issues = new Issues();
        PackManifest m = ManifestParser.parse(bytes(VALID), "fallback", issues);
        assertEquals("Aether Arsenal", m.name());
        assertEquals("aether", m.namespace());
        assertEquals(UUID.fromString("2f0b0c83-8a6b-4e55-9f0a-6a1d6c1f2b11"), m.uuid());
        assertEquals(new Version(1, 2, 3), m.version());
        assertEquals(new Version(1, 21, 4), m.minEngineVersion());
        assertEquals(2, m.modules().size(), "script module must be ignored");
        assertEquals(1, m.dependencies().size(), "script dependency without uuid must be ignored");
        assertEquals(new Version(1, 1, 0), m.dependencies().get(0).minVersion());
        assertEquals(java.util.List.of("Alice", "Bob"), m.authors());
        assertTrue(issues.count(Issues.Level.WARN) >= 2, "ignored module + dependency are reported as warnings");
    }

    @Test
    @DisplayName("derives a namespace from the pack name when the manifest has none")
    void derivesNamespace() throws Exception {
        String json = """
                {"header": {"name": "My Cool Pack!", "uuid": "2f0b0c83-8a6b-4e55-9f0a-6a1d6c1f2b11", "version": [1,0,0]}}
                """;
        Issues issues = new Issues();
        PackManifest m = ManifestParser.parse(bytes(json), "fallback", issues);
        assertEquals("my_cool_pack", m.namespace());
        assertFalse(issues.isEmpty());
    }

    @Test
    @DisplayName("reports every problem at once")
    void collectsAllProblems() {
        String json = """
                {"format_version": 9, "header": {"name": "", "uuid": "not-a-uuid"}}
                """;
        ManifestParser.ManifestException e = assertThrows(ManifestParser.ManifestException.class,
                () -> ManifestParser.parse(bytes(json), "x", new Issues()));
        assertTrue(e.problems().size() >= 4, "problems: " + e.problems());
    }

    @Test
    @DisplayName("rejects reserved and malformed namespaces")
    void rejectsBadNamespaces() {
        for (String ns : new String[]{"minecraft", "mypack", "Has Space", "UPPER"}) {
            String json = "{\"header\":{\"name\":\"n\",\"uuid\":\"2f0b0c83-8a6b-4e55-9f0a-6a1d6c1f2b11\",\"version\":[1,0,0]},"
                    + "\"metadata\":{\"namespace\":\"" + ns + "\"}}";
            assertThrows(ManifestParser.ManifestException.class,
                    () -> ManifestParser.parse(bytes(json), "x", new Issues()), "namespace " + ns);
        }
    }

    @Test
    @DisplayName("rejects documents that are not objects or have no header")
    void rejectsStructuralErrors() {
        assertThrows(ManifestParser.ManifestException.class, () -> ManifestParser.parse(bytes("[1,2]"), "x", new Issues()));
        assertThrows(ManifestParser.ManifestException.class, () -> ManifestParser.parse(bytes("{"), "x", new Issues()));
        assertThrows(ManifestParser.ManifestException.class, () -> ManifestParser.parse(bytes("{}"), "x", new Issues()));
    }
}
