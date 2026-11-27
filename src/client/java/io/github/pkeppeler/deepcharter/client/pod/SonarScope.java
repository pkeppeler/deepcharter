package io.github.pkeppeler.deepcharter.client.pod;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where an echo lands on the navigator's scope (#988): a plan view with the pod at the middle and the way it faces up. The scope's edge is
 * the ping's range, so a ping's whole reach fits and the pod's own movement since the ping slides the echoes toward the edge and off it.
 */
public final class SonarScope {
    /** Blocks above or below the pod's feet at which an echo counts as above or below it, and gets a tick mark over or under its dot. */
    public static final int LEVEL_BLOCKS = 2;

    private SonarScope() {}

    /**
     * An echo's place on the scope.
     *
     * @param x pixels right of the scope's middle pixel
     * @param y pixels below it (up the screen is negative)
     * @param rise 1 when the echo is above the pod by {@link #LEVEL_BLOCKS} or more, -1 when it is below by as much, else 0
     */
    public record Dot(int x, int y, int rise) {}

    /**
     * Projects {@code echo} for a pod at {@code pod} facing {@code facing}, onto a scope {@code radiusPixels} from its middle to its edge that
     * shows {@code rangeBlocks}. Empty when the echo is outside that circle.
     */
    public static Optional<Dot> project(Vec3 pod, Direction facing, BlockPos echo, int rangeBlocks, int radiusPixels) {
        double dx = echo.getX() + 0.5 - pod.x;
        double dz = echo.getZ() + 0.5 - pod.z;
        double ahead = dx * facing.getStepX() + dz * facing.getStepZ();
        // The pod's right hand: facing north (-z) it is east (+x).
        double right = dx * -facing.getStepZ() + dz * facing.getStepX();
        if (ahead * ahead + right * right > (double) rangeBlocks * rangeBlocks) {
            return Optional.empty();
        }
        double pixelsPerBlock = (double) radiusPixels / rangeBlocks;
        // In whole blocks from the pod's feet block, so two up and two down are the same distance.
        int dy = echo.getY() - Mth.floor(pod.y);
        return Optional.of(new Dot(
                (int) Math.round(right * pixelsPerBlock),
                (int) Math.round(-ahead * pixelsPerBlock),
                dy >= LEVEL_BLOCKS ? 1 : dy <= -LEVEL_BLOCKS ? -1 : 0));
    }
}
