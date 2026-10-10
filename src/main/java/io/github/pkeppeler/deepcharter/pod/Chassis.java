package io.github.pkeppeler.deepcharter.pod;

import java.util.List;

import net.minecraft.util.Mth;

import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * The fixed frame a pod is built on (SPEC section 7). Width is the hitbox width and depth; height is its height. They may differ (a
 * tall, narrow cab). The bore, the lining, the sounder, the hangar slot and the tow cable all derive from them: nothing else in the
 * game may assume a size. What cannot be derived stays per chassis: the seat count and the best part tier it takes (SPEC section 7)
 * here, the seat offsets next to its registration in {@link PodRegistry}, and the model in its look file.
 */
public record Chassis(String id, int seats, int tierCap, float width, float height) {
	// 1.9 x 1.9 so that a 2 x 2 bore clears it.
	public static final Chassis MOLE = new Chassis("mole", 1, 2, 1.9f, 1.9f);
	// 2.9 x 2.9 so that a 3 x 3 bore clears it. The pilot's seat, then the navigator's.
	public static final Chassis PROSPECTOR = new Chassis("prospector", 2, 3, 2.9f, 2.9f);

	private static final List<Chassis> SHIPPED = List.of(MOLE, PROSPECTOR);

	/** Every chassis the game ships: the ones with a look, a hangar price and a place in the handbook. A test mod may register more in {@link PodRegistry}. */
	public static List<Chassis> all() {
		return SHIPPED;
	}

	public Chassis {
		if (seats < 1) {
			throw new IllegalArgumentException("a chassis needs at least one seat: " + id);
		}
		int bestTier = 0;
		for (ComponentTrack track : ComponentTrack.values()) {
			bestTier = Math.max(bestTier, track.maxTier());
		}
		// The cap and the seats are adjacent ints, so a swap of the two must not register.
		if (tierCap < 1 || tierCap > bestTier) {
			throw new IllegalArgumentException("a chassis takes part tiers 1 to " + bestTier + ", " + id + " says " + tierCap);
		}
		if (!(width > 0f) || !(height > 0f)) {
			throw new IllegalArgumentException("a chassis needs a width and a height above 0: " + id + " " + width + " x " + height);
		}
	}

	/** Blocks across the bore, on both horizontal axes: the smallest square of whole blocks that clears the hitbox. */
	public int boreWidth() {
		return Mth.ceil(width);
	}

	/** Blocks tall the bore is for a sideways step: the whole blocks that clear the hitbox. */
	public int boreHeight() {
		return Mth.ceil(height);
	}

	/** Blocks in one slab of a downward bore: the cells, and so the ore, a step takes. */
	public int slabCells() {
		return boreWidth() * boreWidth();
	}
}
