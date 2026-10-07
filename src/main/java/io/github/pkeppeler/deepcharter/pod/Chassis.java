package io.github.pkeppeler.deepcharter.pod;

/** The fixed frame a pod is built on; only the Mole exists in M1 (SPEC section 7). Width is the hitbox width and depth. */
public record Chassis(String id, int seats, float width, float height) {
	// 1.9 x 1.9 so that a 2 x 2 bore clears it.
	public static final Chassis MOLE = new Chassis("mole", 1, 1.9f, 1.9f);

	public Chassis {
		if (seats < 1) {
			throw new IllegalArgumentException("a chassis needs at least one seat: " + id);
		}
	}

	public static Chassis byId(String id) {
		if (MOLE.id.equals(id)) {
			return MOLE;
		}
		throw new IllegalArgumentException("unknown pod chassis: " + id);
	}
}
