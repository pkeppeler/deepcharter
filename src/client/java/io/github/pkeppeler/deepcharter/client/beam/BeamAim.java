package io.github.pkeppeler.deepcharter.client.beam;

import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/** What every beam casts through: the client's blocks. One shared instance, because rendering is on one thread. */
public final class BeamAim implements BeamCast.Solid {
    private static final BeamAim INSTANCE = new BeamAim();

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private @Nullable ClientLevel level;

    private BeamAim() {}

    /** The length of a beam from (ox, oy, oz) along the unit vector (dx, dy, dz) in {@code level}, at most {@code reach}: one cast. */
    public static double cast(
            ClientLevel level, double ox, double oy, double oz, double dx, double dy, double dz, double reach) {
        INSTANCE.level = level;
        return BeamCast.length(INSTANCE, ox, oy, oz, dx, dy, dz, reach);
    }

    @Override
    public boolean at(int x, int y, int z) {
        return Objects.requireNonNull(level).getBlockState(pos.set(x, y, z)).isSolidRender();
    }

    /** The x of the unit vector that points along {@code yawDegrees} (a vanilla yaw) and {@code pitchDegrees} below the level. */
    public static double dirX(float yawDegrees, double pitchDegrees) {
        return -Mth.sin(yawDegrees * Mth.DEG_TO_RAD) * Math.cos(Math.toRadians(pitchDegrees));
    }

    public static double dirY(double pitchDegrees) {
        return -Math.sin(Math.toRadians(pitchDegrees));
    }

    public static double dirZ(float yawDegrees, double pitchDegrees) {
        return Mth.cos(yawDegrees * Mth.DEG_TO_RAD) * Math.cos(Math.toRadians(pitchDegrees));
    }
}
