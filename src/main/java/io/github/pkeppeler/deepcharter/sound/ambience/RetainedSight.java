package io.github.pkeppeler.deepcharter.sound.ambience;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Whether a player sees a figure (#861), on a {@link LevelReader} so a server GameTest runs what the client does. Either the head or the
 * body centre counts: a figure in plain view with its head behind a fence or a ceiling lip is seen, not a false "unseen".
 */
public final class RetainedSight {
    private RetainedSight() {}

    /** Whether the player at {@code eyes}, looking along {@code look}, sees the figure by its {@code head} or its {@code body} centre. */
    public static boolean sees(LevelReader level, Vec3 eyes, Vec3 look, Vec3 head, Vec3 body, double halfAngleDegrees) {
        return sees(level, eyes, look, head, halfAngleDegrees) || sees(level, eyes, look, body, halfAngleDegrees);
    }

    private static boolean sees(LevelReader level, Vec3 eyes, Vec3 look, Vec3 target, double halfAngleDegrees) {
        if (!RetainedWarning.inCone(
                look.x, look.y, look.z, target.x - eyes.x, target.y - eyes.y, target.z - eyes.z, halfAngleDegrees)) {
            return false;
        }
        return level.clip(new ClipContext(
                                eyes,
                                target,
                                ClipContext.Block.VISUAL,
                                ClipContext.Fluid.NONE,
                                CollisionContext.empty()))
                        .getType()
                == HitResult.Type.MISS;
    }
}
