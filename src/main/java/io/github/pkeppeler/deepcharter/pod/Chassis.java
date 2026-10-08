package io.github.pkeppeler.deepcharter.pod;

import java.util.List;

/** The fixed frame a pod is built on (SPEC section 7). Width is the hitbox width and depth; it is also the width of the bore. */
public record Chassis(String id, int seats, float width, float height) {
	// 1.9 x 1.9 so that a 2 x 2 bore clears it.
	public static final Chassis MOLE = new Chassis("mole", 1, 1.9f, 1.9f);
	// 2.9 x 2.9 so that a 3 x 3 bore clears it. The pilot's seat, then the navigator's.
	public static final Chassis PROSPECTOR = new Chassis("prospector", 2, 2.9f, 2.9f);

	private static final List<Chassis> ALL = List.of(MOLE, PROSPECTOR);

	public Chassis {
		if (seats < 1) {
			throw new IllegalArgumentException("a chassis needs at least one seat: " + id);
		}
	}

	public static Chassis byId(String id) {
		for (Chassis chassis : ALL) {
			if (chassis.id.equals(id)) {
				return chassis;
			}
		}
		throw new IllegalArgumentException("unknown pod chassis: " + id);
	}
}
