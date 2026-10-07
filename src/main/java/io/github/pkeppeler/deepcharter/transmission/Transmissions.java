package io.github.pkeppeler.deepcharter.transmission;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.resources.Identifier;

/**
 * Fires a transmission for a charter. Any feature calls this when the event a transmission waits
 * for happens; it needs no transmission code to exist yet.
 */
public final class Transmissions {
	// Filled by #62: today this does nothing. #62 keeps this signature, so callers never change.

	private Transmissions() {
	}

	/**
	 * Fires {@code transmission} once for the charter whose id is {@code charterId}; a charter that
	 * has it already is not sent it again. The charter is identified by its UUID because #52 defines
	 * the Charter type after this stub: #52 keys charters by a UUID, and may add an overload that
	 * takes a Charter, but this signature stays.
	 */
	public static void fire(UUID charterId, Identifier transmission) {
		Objects.requireNonNull(charterId, "charterId");
		Objects.requireNonNull(transmission, "transmission");
	}
}
