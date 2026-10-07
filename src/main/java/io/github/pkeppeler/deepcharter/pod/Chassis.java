package io.github.pkeppeler.deepcharter.pod;

/**
 * A pod chassis: the fixed frame a pod is built on. Only the Mole exists in M1; the ladder of
 * larger chassis is in SPEC section 7.
 *
 * @param id     saved with the pod
 * @param seats  how many riders fit
 * @param width  hitbox width and depth, in blocks
 * @param height hitbox height, in blocks
 */
public record Chassis(String id, int seats, float width, float height) {
	/** The founding pod: one seat, and a 1.9 x 1.9 hitbox so that a 2 x 2 bore clears it. */
	public static final Chassis MOLE = new Chassis("mole", 1, 1.9f, 1.9f);

	public Chassis {
		if (seats < 1) {
			throw new IllegalArgumentException("a chassis needs at least one seat: " + id);
		}
	}

	/** The chassis saved under {@code id}. An unknown id fails loudly. */
	public static Chassis byId(String id) {
		if (MOLE.id.equals(id)) {
			return MOLE;
		}
		throw new IllegalArgumentException("unknown pod chassis: " + id);
	}
}
