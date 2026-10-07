package io.github.pkeppeler.deepcharter.transmission;

import java.util.Objects;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.charter.CharterId;

/**
 * Fires a transmission for a charter. Any feature calls this when the event a transmission waits
 * for happens; it needs no transmission code to exist yet.
 */
public final class Transmissions {
	// Filled by #62: today this does nothing. #62 keeps this signature, so callers never change.

	private Transmissions() {
	}

	/**
	 * Fires {@code transmission} once for {@code charter}; a charter that has it already is not sent
	 * it again.
	 */
	public static void fire(CharterId charter, Identifier transmission) {
		Objects.requireNonNull(charter, "charter");
		Objects.requireNonNull(transmission, "transmission");
	}
}
