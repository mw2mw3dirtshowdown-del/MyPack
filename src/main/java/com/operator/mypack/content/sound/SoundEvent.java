package com.operator.mypack.content.sound;

import com.operator.mypack.content.ContentId;

import java.util.List;

/**
 * A custom sound event from {@code sounds/sound_definitions.json}. The event id is {@code <namespace>:<event key>}
 * and is played with {@code player.playSound(location, "<namespace>:<event key>", ...)}.
 *
 * @param category Java sound category ({@code master, music, record, weather, block, hostile, neutral, player,
 *                 ambient, voice})
 */
public record SoundEvent(ContentId id, String category, List<SoundFile> sounds, String subtitle) {

    /**
     * One audio file of an event.
     *
     * @param file pack-relative path without extension, e.g. {@code sounds/aether/fire}; the file is {@code <file>.ogg}
     */
    public record SoundFile(String file, float volume, float pitch, int weight, boolean stream) {
    }
}
