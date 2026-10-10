package io.github.pkeppeler.deepcharter.pod;

import java.util.List;
import java.util.Optional;

/** Gives the tests in other packages the tow reach checks of {@link PodTowing}, which take their tunables as an argument. */
public final class TowReachProbe {
	private TowReachProbe() {
	}

	public static Optional<PodTowing.Refusal> refusal(PodEntity tower, PodEntity towed, TowTuning tuning) {
		return PodTowing.refusal(tower, towed, tuning);
	}

	public static List<PodEntity> towedBy(PodEntity tower, TowTuning tuning) {
		return PodTowing.towedBy(tower, tuning);
	}
}
