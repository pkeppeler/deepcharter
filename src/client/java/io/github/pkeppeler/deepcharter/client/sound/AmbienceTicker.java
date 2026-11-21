package io.github.pkeppeler.deepcharter.client.sound;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceFragments;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceScheduler;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTuning;
import io.github.pkeppeler.deepcharter.sound.ambience.QuietTracker;
import io.github.pkeppeler.deepcharter.sound.ambience.SpotPicker;
import java.util.Optional;
import java.util.SplittableRandom;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The First Sounds ambience (#866): faint 1850s and 1860s voices in the dark, building with depth. Every tick it asks an
 * {@link AmbienceScheduler} whether a fragment is due and plays it positionally, far off, as ambient sound: from a place out of sight
 * ({@link SpotPicker}), or, near a Company horn ({@link HornFinder}), from the horn after a crackle of warm-up. Per player and client
 * only: it is atmosphere and carries no rule, so the server knows nothing of it. Nothing starts while the Lamp Hymn, a phonograph, a
 * transmission or another fragment sounds, or just after ({@link AmbienceTuning#quietAfterTicks()}); the tracker learns of them from the
 * sound manager's own listener, which the subtitles use.
 */
public final class AmbienceTicker {
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final HornFinder HORNS = new HornFinder();

    private static AmbienceTuning tuning = AmbienceTuning.DEFAULT;
    private static AmbienceScheduler scheduler = newScheduler();
    private static @Nullable QuietTracker<SoundInstance> quiet;
    private static boolean listening;

    /** A tick count that only goes forward, in this class's own time: the game's clock stops when the world is left. */
    private static long now;

    private AmbienceTicker() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(AmbienceTicker::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    /**
     * Swaps the tuning and forgets every timer and sound.
     *
     * @apiNote only a client GameTest calls this
     */
    // test-seam: the tuning's gaps are minutes of play, which a client test cannot wait out, so it swaps in a tuning of
    // a few ticks
    public static void restart(AmbienceTuning next) {
        tuning = next;
        scheduler = newScheduler();
        quiet = null;
        HORNS.clear();
    }

    private static AmbienceScheduler newScheduler() {
        return new AmbienceScheduler(tuning, AmbienceFragments.pools(), RANDOM.split());
    }

    private static void reset() {
        scheduler.reset();
        HORNS.clear();
        if (quiet != null) {
            quiet.clear();
        }
    }

    /** Listens to the sound manager once, as soon as there is one, and starts a tracker (again after a restart). */
    private static QuietTracker<SoundInstance> listen(SoundManager sounds) {
        if (!listening) {
            sounds.addListener((sound, events, range) -> {
                if (quiet != null && holdsBack(sound)) {
                    quiet.started(sound, now);
                }
            });
            listening = true;
        }
        if (quiet == null) {
            quiet = new QuietTracker<>(sounds::isActive, tuning.quietAfterTicks());
        }
        return quiet;
    }

    /** True for a sound of ours that the ambience gives way to: a hymn, a phonograph, a transmission, or an ambience fragment. */
    private static boolean holdsBack(SoundInstance sound) {
        if (!sound.getIdentifier().getNamespace().equals(DeepCharter.MOD_ID)) {
            return false;
        }
        String path = sound.getIdentifier().getPath();
        return tuning.quietPrefixes().stream().anyMatch(path::startsWith);
    }

    private static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }
        SoundManager sounds = client.getSoundManager();
        QuietTracker<SoundInstance> tracker = listen(sounds);
        if (client.isPaused()) {
            return;
        }
        now++;
        BlockPos feet = client.player.blockPosition();
        int layer = LayerChain.bandAt(level, feet.getY())
                .map(LayerChain.Band::layer)
                .orElse(LayerChain.SURFACE);
        Optional<BlockPos> horn = layer == LayerChain.SURFACE
                ? Optional.empty()
                : HORNS.nearest(level, layer, feet, now, tuning.hornRange());
        Optional<AmbienceScheduler.Pick> pick = scheduler.tick(layer, horn.isPresent(), tracker.quiet(now));
        if (pick.isEmpty()) {
            return;
        }
        SoundEvent fragment =
                AmbienceFragments.sound(pick.get().tier(), pick.get().fragment());
        if (pick.get().source() == AmbienceScheduler.Source.HORN && horn.isPresent()) {
            Vec3 at = Vec3.atCenterOf(horn.get());
            sounds.play(at(DeepSound.AMBIENCE_HORN_WARMUP.event(), at));
            sounds.playDelayed(at(fragment, at), tuning.hornWarmupTicks());
        } else {
            SpotPicker.pick(level, client.player.getEyePosition(), tuning, RANDOM)
                    .ifPresent(spot -> sounds.play(at(fragment, spot)));
        }
    }

    private static SimpleSoundInstance at(SoundEvent event, Vec3 at) {
        return new SimpleSoundInstance(
                event, SoundSource.AMBIENT, 1f, 1f, RandomSource.create(RANDOM.nextLong()), at.x, at.y, at.z);
    }
}
