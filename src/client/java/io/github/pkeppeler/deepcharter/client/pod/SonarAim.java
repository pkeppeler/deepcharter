package io.github.pkeppeler.deepcharter.client.pod;

import io.github.pkeppeler.deepcharter.pod.SonarEcho;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

/** Which echo the navigator means to mark: the one nearest the direction they look, within a few degrees (#988). */
public final class SonarAim {
    private SonarAim() {}

    /**
     * The echo of {@code echoes} whose block's middle lies at the smallest angle from {@code look} as seen from {@code eye}, when that angle is at
     * most {@code maxDegrees}. Empty when none is that near, or {@code look} has no direction.
     */
    public static Optional<SonarEcho> pick(Vec3 eye, Vec3 look, List<SonarEcho> echoes, double maxDegrees) {
        if (look.lengthSqr() == 0) {
            return Optional.empty();
        }
        Vec3 aim = look.normalize();
        double bestCos = Math.cos(Math.toRadians(maxDegrees));
        Optional<SonarEcho> best = Optional.empty();
        for (SonarEcho echo : echoes) {
            Vec3 to = Vec3.atCenterOf(echo.pos()).subtract(eye);
            if (to.lengthSqr() == 0) {
                continue;
            }
            double cos = to.normalize().dot(aim);
            if (cos >= bestCos) {
                bestCos = cos;
                best = Optional.of(echo);
            }
        }
        return best;
    }
}
