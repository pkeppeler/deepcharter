package io.github.pkeppeler.deepcharter.client.sound;

import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.SoundTuning;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * The Retained's dread bed (#1450, ADR 0109): one low loop while a figure holds this client's player on foot, or the pod they ride, pilot
 * or passenger ({@link LamplessFigure#holds}). It rises over {@link SoundTuning#dreadFadeInTicks} and, once nothing holds them (the figure
 * struck off or cut away, the pod gone), falls over {@link SoundTuning#dreadFadeOutTicks} and stops. There is one bed per client, however
 * many figures hold on: a second grip while one plays keeps the same bed, and a grip that comes back while the bed is still fading turns
 * it up again. A pod's lights do not end it, because a light frees no one a figure already holds. While it plays, the layer's drone is
 * turned down by {@link #droneGain} ({@code BiomeLoopDuckMixin}).
 *
 * <p>It plays from the cab, not from a place: the bed is what holding feels like to those held. Bystanders hear the figure, not the bed.
 * It is in the hostile creatures' slider, so a player who turns the music off still hears that something has hold of them.
 */
public final class DreadBed {
    /** Ticks a started bed may take to show up in the sound engine before it counts as dropped. */
    private static final int START_GRACE_TICKS = 3;

    private static @Nullable Bed playing;

    private DreadBed() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(DreadBed::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> playing = null);
    }

    private static void tick(Minecraft client) {
        Bed bed = playing;
        // The engine can drop a loop without stopping it (SoundManager.stop() at a level change or a death), and it may
        // decline one: a bed it does not play is gone, as vanilla's MusicManager reads it.
        if (bed != null
                && (bed.isStopped()
                        || (++bed.ageTicks > START_GRACE_TICKS
                                && !client.getSoundManager().isActive(bed)))) {
            playing = null;
        }
        if (playing == null && held(client)) {
            Bed started = new Bed();
            playing = started;
            client.getSoundManager().play(started);
        }
    }

    /** Whether a figure holds the player at this client or the pod they ride. */
    private static boolean held(Minecraft client) {
        LocalPlayer player = client.player;
        return player != null && client.level != null && LamplessFigure.holds(client.level, player);
    }

    /**
     * The share of its volume the layer's drone keeps now: 1 with no bed, down to {@link SoundTuning#droneDuck} with the bed at full, and
     * in between as the bed fades. Read by {@code BiomeLoopDuckMixin} on each tick of the drone.
     */
    public static float droneGain() {
        Bed bed = playing;
        float bedLevel = bed == null || bed.isStopped() ? 0f : bed.fade();
        return 1f - (1f - SoundTuning.DEFAULT.droneDuck()) * bedLevel;
    }

    /** The loop: rises while the player is held, falls while they are not, and stops once it is silent and no one holds them. */
    private static final class Bed extends AbstractTickableSoundInstance {
        private final float riseStep = 1f / SoundTuning.DEFAULT.dreadFadeInTicks();
        private final float fallStep = 1f / SoundTuning.DEFAULT.dreadFadeOutTicks();
        private int ageTicks;

        private Bed() {
            super(DeepSound.CREATURE_RETAINED_DREAD.event(), SoundSource.HOSTILE, RandomSource.create());
            this.looping = true;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0f;
        }

        /** The bed starts at volume 0 and rises; vanilla would drop a silent sound without playing it. */
        @Override
        public boolean canStartSilent() {
            return true;
        }

        /** How far the bed has risen, 0 to 1: its own volume, before the sounds.json volume that {@code getVolume} multiplies in. */
        private float fade() {
            return volume;
        }

        @Override
        public void tick() {
            boolean held = held(Minecraft.getInstance());
            volume = Mth.clamp(volume + (held ? riseStep : -fallStep), 0f, 1f);
            if (!held && volume == 0f) {
                stop();
            }
        }
    }
}
