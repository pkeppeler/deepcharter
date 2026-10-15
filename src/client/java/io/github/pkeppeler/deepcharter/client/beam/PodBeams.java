package io.github.pkeppeler.deepcharter.client.beam;

import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderState;
import io.github.pkeppeler.deepcharter.client.theme.BeamLook;
import io.github.pkeppeler.deepcharter.client.theme.BeamStyle;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLights;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The headlamp beam of a lit pod (#248). A pod shows one exactly when it holds a light block (ADR 0024): it has power (not stranded, not a
 * wreck) and a lights part, and the part's light level sets how far (a better part throws farther). An unlit pod shows none, a parked
 * pod with its lights on shows one, and the beam adds no light of its own. The beam points where the pod faces and tilts down by the style's pitch, and
 * where the pod drills it follows the drill mount towards straight down.
 *
 * <p>One raycast per lit pod per frame, through the blocks the client has loaded ({@link BeamAim}, at most {@code reach} blocks of
 * grid); an unlit pod, and one past the style's draw distance, casts none. The beam of each pod is kept and set again.
 */
public final class PodBeams {
    private static final Map<PodEntity, BeamDraw> BEAMS = new WeakHashMap<>();

    private PodBeams() {}

    /** How far the pod's beam reaches in open air, 0 when it shows none. Used to widen the box the game culls the pod by, so a beam in view keeps its pod drawn. */
    public static double reach(PodEntity pod) {
        return reach(pod, BeamLook.current().headlamp());
    }

    private static double reach(PodEntity pod, BeamStyle style) {
        return BeamCast.reach(PodEvents.isPowered(pod), PodLights.partLevel(pod), style.length());
    }

    /**
     * The beam of {@code pod} for the frame {@code state} describes, or null when it shows none: the pod is dark or too far, or its lamp
     * is inside a block. The beam is in the pod's own space (the origin at its feet) for the submit that follows.
     */
    public static @Nullable BeamDraw beamFor(PodEntity pod, PodGeoRenderState state) {
        BeamLook look = BeamLook.current();
        BeamStyle style = look.headlamp();
        double reach = reach(pod, style);
        ClientLevel level = Minecraft.getInstance().level;
        double drawDistance = look.drawDistance();
        if (reach <= 0 || level == null || state.distanceToCameraSq > drawDistance * drawDistance) {
            return null;
        }
        float yaw = state.heading;
        double pitch = state.drilling ? Math.max(style.pitch(), state.mountPitch) : style.pitch();
        double dx = BeamAim.dirX(yaw, pitch);
        double dy = BeamAim.dirY(pitch);
        double dz = BeamAim.dirZ(yaw, pitch);
        // The lamp sits ahead of the pod's middle along where it faces (level), at the style's height.
        double ox = -Mth.sin(yaw * Mth.DEG_TO_RAD) * style.forward();
        double oz = Mth.cos(yaw * Mth.DEG_TO_RAD) * style.forward();
        double oy = style.height();
        double length = BeamAim.cast(level, state.x + ox, state.y + oy, state.z + oz, dx, dy, dz, reach);
        if (length <= 0) {
            return null;
        }
        BeamDraw beam = BEAMS.get(pod);
        if (beam == null) {
            beam = new BeamDraw(style);
            BEAMS.put(pod, beam);
        }
        beam.aim(style, look.sides(), reach, length, ox, oy, oz, dx, dy, dz);
        return beam;
    }
}
