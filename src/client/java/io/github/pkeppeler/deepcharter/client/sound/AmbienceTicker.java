package io.github.pkeppeler.deepcharter.client.sound;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceFragments;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceScheduler;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTuning;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockCaller;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockHour;
import io.github.pkeppeler.deepcharter.sound.ambience.ClockTuning;
import io.github.pkeppeler.deepcharter.sound.ambience.PunchClock;
import io.github.pkeppeler.deepcharter.sound.ambience.QuietTracker;
import io.github.pkeppeler.deepcharter.sound.ambience.RetainedSight;
import io.github.pkeppeler.deepcharter.sound.ambience.RetainedWarning;
import io.github.pkeppeler.deepcharter.sound.ambience.SpotPicker;
import io.github.pkeppeler.deepcharter.sound.ambience.WarningTuning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The First Sounds ambience (#866): faint 1850s and 1860s voices in the dark, building with depth. Every tick it asks an
 * {@link AmbienceScheduler} whether a fragment is due and plays it positionally, far off, as ambient sound: from a place out of sight
 * ({@link SpotPicker}), or, near a Company horn ({@link HornFinder}), from the horn after a crackle of warm-up. Per player and client
 * only: it is atmosphere and carries no rule, so the server knows nothing of it. Nothing starts while the Lamp Hymn, a phonograph, a
 * transmission or another fragment sounds, or just after ({@link AmbienceTuning#quietAfterTicks()}); the tracker learns of them from the
 * sound manager's own listener, which the subtitles use.
 *
 * <p>The warning in the dark (#861) is one more source of the same ticker, with the same quiet rule and the same way of playing a sound:
 * when a Retained is near and unseen, {@link RetainedWarning} says so, and the dark's variant of "Au clair de la lune" comes from the
 * figure. It never comes from the scheduler's timers, and not near a wreck's phonograph, where the tune is learnt. The decisions are in
 * {@code src/main} ({@link RetainedWarning}, {@link RetainedSight}); this class gathers the figures and plays the sound.
 */
public final class AmbienceTicker {
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final BlockFinder HORNS = new BlockFinder(AmbienceRegistry.COMPANY_HORN);
    private static final BlockFinder CLOCKS = new BlockFinder(AmbienceRegistry.COMPANY_CLOCK);

    private static AmbienceTuning tuning = AmbienceTuning.DEFAULT;
    private static AmbienceScheduler scheduler = newScheduler();
    private static final WarningTuning WARNING_TUNING = WarningTuning.DEFAULT;
    private static final RetainedWarning WARNING = new RetainedWarning(WARNING_TUNING);
    private static final ClockTuning CLOCK_TUNING = ClockTuning.DEFAULT;
    private static ClockCaller clockCaller = new ClockCaller(CLOCK_TUNING, RANDOM.split());

    private static @Nullable QuietTracker<SoundInstance> quiet;
    /** The wreck phonograph's song alone, and for longer after it than the rest: the warning keeps away from it. */
    private static @Nullable QuietTracker<SoundInstance> wreckSong;

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
        wreckSong = null;
        WARNING.reset();
        HORNS.clear();
        CLOCKS.clear();
        clockCaller = new ClockCaller(CLOCK_TUNING, RANDOM.split());
    }

    private static AmbienceScheduler newScheduler() {
        return new AmbienceScheduler(tuning, AmbienceFragments.pools(), AmbienceFragments.hornPool(), RANDOM.split());
    }

    private static void reset() {
        scheduler.reset();
        clockCaller.reset();
        WARNING.reset();
        HORNS.clear();
        CLOCKS.clear();
        if (quiet != null) {
            quiet.clear();
        }
        if (wreckSong != null) {
            wreckSong.clear();
        }
    }

    /** Listens to the sound manager once, as soon as there is one, and starts a tracker (again after a restart). */
    private static QuietTracker<SoundInstance> listen(SoundManager sounds) {
        if (!listening) {
            sounds.addListener((sound, events, range) -> {
                if (quiet != null && holdsBack(sound)) {
                    quiet.started(sound, now);
                }
                if (wreckSong != null && isWreckSong(sound)) {
                    wreckSong.started(sound, now);
                }
            });
            listening = true;
        }
        if (quiet == null) {
            quiet = new QuietTracker<>(sounds::isActive, tuning.quietAfterTicks());
        }
        if (wreckSong == null) {
            wreckSong = new QuietTracker<>(sounds::isActive, WARNING_TUNING.wreckQuietTicks());
        }
        return quiet;
    }

    private static boolean isWreckSong(SoundInstance sound) {
        return sound.getIdentifier().equals(DeepSound.SONG_WRECK.event().location());
    }

    /** True for a sound of ours that the ambience gives way to: a hymn, a phonograph, a transmission, an ambience fragment or a song. */
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
        Optional<BlockPos> horn = nearestBlock(HORNS, level, layer, feet, tuning.hornRange());
        boolean quietNow = tracker.quiet(now);
        boolean warned = warn(client, level, quietNow);
        boolean called = callHour(sounds, level, layer, feet, quietNow || warned);
        // A fragment due on the tick the song or a call sounds waits, as it does for any sound.
        Optional<AmbienceScheduler.Pick> pick = scheduler.tick(layer, horn.isPresent(), quietNow || warned || called);
        if (pick.isEmpty()) {
            return;
        }
        SoundEvent fragment = pick.get().pool() == AmbienceScheduler.Pool.HORN
                ? AmbienceFragments.hornSound(pick.get().fragment())
                : AmbienceFragments.sound(pick.get().tier(), pick.get().fragment());
        if (pick.get().source() == AmbienceScheduler.Source.HORN && horn.isPresent()) {
            Vec3 at = Vec3.atCenterOf(horn.get());
            sounds.play(at(DeepSound.AMBIENCE_HORN_WARMUP.event(), at));
            sounds.playDelayed(at(fragment, at), tuning.hornWarmupTicks());
        } else {
            SpotPicker.pick(level, client.player.getEyePosition(), tuning, RANDOM)
                    .ifPresent(spot -> sounds.play(at(fragment, spot)));
        }
    }

    /** The nearest block a finder looks for within {@code range}, never on the surface. */
    private static Optional<BlockPos> nearestBlock(
            BlockFinder finder, ClientLevel level, int layer, BlockPos feet, int range) {
        return layer == LayerChain.SURFACE ? Optional.empty() : finder.nearest(level, layer, feet, now, range);
    }

    /**
     * The punch clock (#938): {@link PunchClock} decides, near a Company clock, whether it calls an hour; this strikes the hour or hours it
     * names from the clock, one after the other. Returns whether it did.
     */
    private static boolean callHour(
            SoundManager sounds, ClientLevel level, int layer, BlockPos feet, boolean quietNow) {
        Optional<PunchClock.Strike> called = PunchClock.tick(
                clockCaller,
                nearestBlock(CLOCKS, level, layer, feet, CLOCK_TUNING.range()),
                ClockHour.of(level),
                quietNow);
        if (called.isEmpty()) {
            return false;
        }
        Vec3 at = Vec3.atCenterOf(called.get().at());
        for (ClockCaller.Chime chime : called.get().call().chimes()) {
            SoundEvent strike = AmbienceFragments.clockHour(chime.hour());
            if (chime.delayTicks() == 0) {
                sounds.play(at(strike, at));
            } else {
                sounds.playDelayed(at(strike, at), chime.delayTicks());
            }
        }
        return true;
    }

    /**
     * The warning in the dark: tells {@link RetainedWarning} of each Retained within {@link WarningTuning#forgetRange()} and whether the
     * player sees it, and plays the song from the figure it names. Returns whether it played.
     */
    private static boolean warn(Minecraft client, ClientLevel level, boolean quietNow) {
        Player player = Objects.requireNonNull(client.player);
        List<RetainedWarning.Figure> figures = new ArrayList<>();
        Map<Integer, Entity> known = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof LamplessFigure figure && figure.isAlive()) {
                double distance = figure.position().distanceTo(player.position());
                if (distance <= WARNING_TUNING.forgetRange()) {
                    boolean seen = RetainedSight.sees(
                            level,
                            player.getEyePosition(),
                            player.getLookAngle(),
                            figure.getEyePosition(),
                            figure.position().add(0, figure.getBbHeight() / 2, 0),
                            WARNING_TUNING.viewHalfAngle());
                    figures.add(new RetainedWarning.Figure(figure.getId(), distance, seen));
                    known.put(figure.getId(), figure);
                }
            }
        }
        OptionalInt from = WARNING.tick(
                figures,
                quietNow,
                Objects.requireNonNull(wreckSong, "set by listen()").quiet(now));
        if (from.isEmpty()) {
            return false;
        }
        @Nullable Entity figure = known.get(from.getAsInt());
        if (figure == null) {
            return false;
        }
        client.getSoundManager().play(at(DeepSound.SONG_DARK.event(), figure.getEyePosition()));
        return true;
    }

    private static SimpleSoundInstance at(SoundEvent event, Vec3 at) {
        return new SimpleSoundInstance(
                event, SoundSource.AMBIENT, 1f, 1f, RandomSource.create(RANDOM.nextLong()), at.x, at.y, at.z);
    }
}
