package io.github.pkeppeler.deepcharter.client.continuity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;

/**
 * The hard cut's silence: from the death until the sequence ends (the respawn, or leaving the world), every sound the game starts is
 * refused except the event's own ({@link SequenceSound}). Read by {@code SoundEngineMixin} on the client thread and the sound engine's.
 */
public final class Silence {
    private static volatile boolean held;

    private Silence() {}

    /** Stops every sound now playing and refuses every new one but the event's. */
    static void begin(Minecraft client) {
        held = true;
        client.getSoundManager().stop();
    }

    static void end() {
        held = false;
    }

    /** Whether the engine refuses {@code sound}. */
    public static boolean refuses(SoundInstance sound) {
        return held && !(sound instanceof SequenceSound);
    }
}
