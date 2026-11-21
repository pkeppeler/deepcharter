package io.github.pkeppeler.deepcharter.sound.ambience;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Whether a player sees a figure (#861): the figure's head is inside the player's view cone ({@link RetainedWarning#inCone}) and no solid
 * block lies between the eyes and the head. A pure function of a {@link LevelReader}, so the server's level answers as the client's does and a
 * server GameTest runs it; the client only gathers the figures and the player's eyes and look.
 */
public final class RetainedSight {
    private RetainedSight() {}

    /**
     * Whether the player at {@code eyes}, looking along {@code look}, sees the figure whose head is at {@code head}.
     *
     * @param eyes the player's eyes
     * @param look the direction the player looks (any length)
     * @param head where the figure's eyes are
     * @param halfAngleDegrees the half angle of the view cone ({@link WarningTuning#viewHalfAngle()})
     */
    public static boolean sees(LevelReader level, Vec3 eyes, Vec3 look, Vec3 head, double halfAngleDegrees) {
        if (!RetainedWarning.inCone(
                look.x, look.y, look.z, head.x - eyes.x, head.y - eyes.y, head.z - eyes.z, halfAngleDegrees)) {
            return false;
        }
        return level.clip(new ClipContext(
                                eyes, head, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()))
                        .getType()
                == HitResult.Type.MISS;
    }
}
