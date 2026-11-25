package io.github.pkeppeler.deepcharter.sound.ambience;

import java.util.Optional;
import java.util.random.RandomGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Picks the place a fragment from the dark seems to come from: the best of {@link AmbienceTuning#spotTries()} places
 * {@link AmbienceTuning#distanceMin()} to {@link AmbienceTuning#distanceMax()} blocks from the listener's eyes (and up to 4 above or below).
 * Out of sight is best (rock between), then air, then dark, so the voice seems to come from behind a wall, in a hollow, where no lamp is.
 * A pure function of a {@link LevelReader} and a random, so the server's level answers as the client's does.
 */
public final class SpotPicker {
    /** The most a place may be above or below the eyes. */
    private static final double LIFT = 4;

    /** Block light up to this is dark. */
    private static final int DARK = 3;

    private SpotPicker() {}

    /** The best place, or empty when none of the tries lies in a loaded chunk. */
    public static Optional<Vec3> pick(LevelReader level, Vec3 eyes, AmbienceTuning tuning, RandomGenerator random) {
        @Nullable Vec3 best = null;
        int bestScore = -1;
        for (int i = 0; i < tuning.spotTries(); i++) {
            double angle = random.nextDouble(Math.PI * 2);
            double distance = random.nextDouble(tuning.distanceMin(), tuning.distanceMax());
            Vec3 at = eyes.add(Math.cos(angle) * distance, random.nextDouble(-LIFT, LIFT), Math.sin(angle) * distance);
            BlockPos pos = BlockPos.containing(at);
            if (level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null) {
                continue;
            }
            int score = score(level, eyes, at);
            if (score > bestScore) {
                best = at;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Rock between the eyes and the place counts 4, air at the place 2, and no lamp by it 1. */
    public static int score(LevelReader level, Vec3 eyes, Vec3 at) {
        BlockPos pos = BlockPos.containing(at);
        int score = 0;
        if (level.clip(new ClipContext(
                                eyes, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
                        .getType()
                == HitResult.Type.BLOCK) {
            score += 4;
        }
        if (level.getBlockState(pos).isAir()) {
            score += 2;
        }
        if (level.getBrightness(LightLayer.BLOCK, pos) <= DARK) {
            score += 1;
        }
        return score;
    }
}
