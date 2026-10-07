package io.github.pkeppeler.deepcharter.pod;

/**
 * Tunables for the pod feature, one nested record per part so that parallel changes do not touch
 * the same lines. Add one component per tunable to your part's record and give it its value where
 * that record's {@code DEFAULT} is built; read it as {@code PodTuning.DEFAULT.movement().thing()}.
 */
public record PodTuning(Movement movement, Drill drill, Cargo cargo, Fuel fuel) {
	public static final PodTuning DEFAULT = new PodTuning(Movement.DEFAULT, Drill.DEFAULT, Cargo.DEFAULT, Fuel.DEFAULT);

	/** Filled by #29. */
	public record Movement() {
		public static final Movement DEFAULT = new Movement();
	}

	/** Filled by #30. */
	public record Drill() {
		public static final Drill DEFAULT = new Drill();
	}

	/** Filled by #31. */
	public record Cargo() {
		public static final Cargo DEFAULT = new Cargo();
	}

	/** Filled by #31. */
	public record Fuel() {
		public static final Fuel DEFAULT = new Fuel();
	}
}
