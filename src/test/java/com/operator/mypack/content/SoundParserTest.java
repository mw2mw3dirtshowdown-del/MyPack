package com.operator.mypack.content;

import com.operator.mypack.content.sound.SoundEvent;
import com.operator.mypack.content.sound.SoundParser;
import com.operator.mypack.pack.Issues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.operator.mypack.content.TestJson.ctx;
import static com.operator.mypack.content.TestJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundParserTest {

    @Test
    @DisplayName("parses Bedrock sound_definitions: string and object entries, pitch ranges, extension stripping")
    void parsesDefinitions() {
        Issues issues = new Issues();
        List<SoundEvent> events = SoundParser.parse(obj("""
                {"format_version": "1.14.0", "sound_definitions": {
                  "Aether.Blaster.Fire": {"category": "player", "subtitle": "Blaster fires", "sounds": [
                      "sounds/aether/fire1",
                      {"name": "sounds/aether/fire2.ogg", "volume": 0.5, "pitch": [0.8, 1.2], "weight": 3, "stream": true},
                      {"name": "aether/fire3"}]},
                  "aether.hum": {"sounds": ["sounds/hum.wav"]},
                  "aether.empty": {"sounds": []},
                  "bad name!": {"sounds": ["sounds/x"]}
                }}
                """), ctx(issues));
        assertEquals(2, events.size(), "empty and badly named events are skipped");
        SoundEvent fire = events.get(0);
        assertEquals("aether:aether.blaster.fire", fire.id().full(), "event keys are lower-cased and namespaced");
        assertEquals("player", fire.category());
        assertEquals(3, fire.sounds().size());
        assertEquals("sounds/aether/fire2", fire.sounds().get(1).file());
        assertEquals(1.0f, fire.sounds().get(1).pitch(), 1e-6, "pitch range becomes its midpoint");
        assertEquals(0.5f, fire.sounds().get(1).volume());
        assertEquals(3, fire.sounds().get(1).weight());
        assertTrue(fire.sounds().get(1).stream());
        assertEquals("sounds/aether/fire3", fire.sounds().get(2).file(), "missing 'sounds/' prefix is added");
        assertTrue(issues.all().stream().anyMatch(i -> i.message().contains("not .ogg")), "wav is flagged");
    }

    @Test
    @DisplayName("path traversal in sound file names is rejected")
    void rejectsTraversal() {
        List<SoundEvent> events = SoundParser.parse(obj("""
                {"sound_definitions": {"a.b": {"sounds": ["../../etc/passwd", "sounds/ok"]}}}
                """), ctx(new Issues()));
        assertEquals(1, events.size());
        assertEquals(1, events.get(0).sounds().size());
        assertEquals("sounds/ok", events.get(0).sounds().get(0).file());
    }
}
