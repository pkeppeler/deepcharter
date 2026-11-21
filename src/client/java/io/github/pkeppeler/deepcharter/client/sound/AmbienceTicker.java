package io.github.pkeppeler.deepcharter.client.sound;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceFragments;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceScheduler;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceTuning;
import io.github.pkeppeler.deepcharter.sound.ambience.QuietTracker;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The First Sounds ambience (#866): faint 1850s and 1860s voices in the dark, building with depth. Every tick it asks an
 * {@link AmbienceScheduler} whether a fragment is due and plays it positionally, far off, as ambient sound: from a place out of sight
 * 12 to 28 blocks away, or, near a Company horn ({@link HornFinder}), from the horn after a crackle of warm-up. Per player and client
 * only: it is atmosphere and carries no rule, so the server knows nothing of it. Nothing starts while the Lamp Hymn, a phonograph, a
 * transmission or another fragment sounds, or just after ({@link AmbienceTuning#quietAfterTicks()}); the tracker learns of them from the
 * sound manager's own listener, which the subtitles use. A later speaker in a wreck (#861) is one more source for the same scheduler.
 */
public final class AmbienceTicker {
    private static final AmbienceTuning TUNING = AmbienceTuning.DEFAULT;

    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final AmbienceScheduler SCHEDULER =
            new AmbienceScheduler(TUNING, AmbienceFragments.pools(), RANDOM.split());
    private static final HornFinder HORNS = new HornFinder();

    /** A tick count that only goes forward, in this class's own time: the game's clock stops when the world is left. */
    private static long now;

    private static @Nullable QuietTracker<SoundInstance> quiet;

    private AmbienceTicker() {}

    /** Listens to the sound manager once, as soon as there is one. */
    private static void listen(SoundManager sounds) {
        if (quiet != null) {
            return;
        }
        QuietTracker<SoundInstance> tracker = new QuietTracker<>(sounds::isActive, TUNING.quietAfterTicks());
        quiet = tracker;
        sounds.addListener((SoundInstance sound, WeighedSoundEvents events, float range) -> {
            if (holdsBack(sound)) {
                tracker.started(sound, now);
            }
        });
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(AmbienceTicker::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void reset() {
        SCHEDULER.reset();
        HORNS.clear();
        if (quiet != null) {
            quiet.clear();
        }
    }

    /** True for a sound of ours that the ambience gives way to: a hymn, a phonograph, a transmission, or an ambience fragment. */
    private static boolean holdsBack(SoundInstance sound) {
        if (!sound.getIdentifier().getNamespace().equals(DeepCharter.MOD_ID)) {
            return false;
        }
        String path = sound.getIdentifier().getPath();
        return TUNING.quietPrefixes().stream().anyMatch(path::startsWith);
    }

    private static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }
        listen(client.getSoundManager());
        if (client.isPaused()) {
            return;
        }
        now++;
        BlockPos feet = client.player.blockPosition();
        int layer = LayerChain.bandAt(level, feet.getY())
                .map(LayerChain.Band::layer)
                .orElse(LayerChain.SURFACE);
        Optional<BlockPos> horn = layer == LayerChain.SURFACE ? Optional.empty() : HORNS.nearest(level, feet, now);
        Optional<AmbienceScheduler.Pick> pick = SCHEDULER.tick(
                layer,
                horn.isPresent(),
                Objects.requireNonNull(quiet, "set by listen() above").quiet(now));
        if (pick.isEmpty()) {
            return;
        }
        SoundEvent fragment =
                AmbienceFragments.sound(pick.get().tier(), pick.get().fragment());
        SoundManager sounds = client.getSoundManager();
        if (pick.get().source() == AmbienceScheduler.Source.HORN && horn.isPresent()) {
            Vec3 at = Vec3.atCenterOf(horn.get());
            sounds.play(at(DeepSound.AMBIENCE_HORN_WARMUP.event(), at));
            sounds.playDelayed(at(fragment, at), TUNING.hornWarmupTicks());
        } else {
            sounds.play(at(fragment, spotFor(level, client.player)));
        }
    }

    private static SimpleSoundInstance at(SoundEvent event, Vec3 at) {
        return new SimpleSoundInstance(
                event, SoundSource.AMBIENT, 1f, 1f, RandomSource.create(RANDOM.nextLong()), at.x, at.y, at.z);
    }

    /**
     * A place for a fragment from the dark: the best of {@link AmbienceTuning#spotTries()} places 12 to 28 blocks away. Out of sight is
     * best (rock between), then air, then dark, so the voice seems to come from behind a wall, in a hollow, where no lamp is.
     */
    private static Vec3 spotFor(ClientLevel level, Player player) {
        Vec3 eyes = player.getEyePosition();
        Vec3 best = eyes;
        int bestScore = -1;
        for (int i = 0; i < TUNING.spotTries(); i++) {
            double angle = RANDOM.nextDouble(Math.PI * 2);
            double distance = TUNING.distanceMin() + RANDOM.nextDouble(TUNING.distanceMax() - TUNING.distanceMin());
            double lift = RANDOM.nextDouble(-4, 4);
            Vec3 at = eyes.add(Math.cos(angle) * distance, lift, Math.sin(angle) * distance);
            BlockPos pos = BlockPos.containing(at);
            if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            int score = 0;
            if (level.clip(new ClipContext(eyes, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                            .getType()
                    == HitResult.Type.BLOCK) {
                score += 4;
            }
            if (level.getBlockState(pos).isAir()) {
                score += 2;
            }
            if (level.getBrightness(LightLayer.BLOCK, pos) <= 3) {
                score += 1;
            }
            if (score > bestScore) {
                best = at;
                bestScore = score;
            }
        }
        return bestScore < 0 ? eyes.add(0, 0, TUNING.distanceMax()) : best;
    }
}
