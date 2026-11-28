package io.github.pkeppeler.deepcharter.pod;

import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** Gives the tests in other packages the tow reach checks of {@link PodTowing}, which take their tunables as an argument, and a way to age a pick. */
public final class TowReachProbe {
    private TowReachProbe() {}

    public static Optional<PodTowing.Refusal> refusal(PodEntity tower, PodEntity towed, TowTuning tuning) {
        return PodTowing.refusal(tower, towed, tuning);
    }

    public static List<PodEntity> towedBy(PodEntity tower, TowTuning tuning) {
        return PodTowing.towedBy(tower, tuning);
    }

    /** Makes the person's pick {@code ticks} older, so a test need not wait out {@link TowTuning#pickTicks}. */
    public static void agePick(ServerPlayer person, long ticks) {
        PodTowing.agePick(person, ticks);
    }
}
