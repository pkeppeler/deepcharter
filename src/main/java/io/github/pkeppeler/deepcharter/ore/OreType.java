package io.github.pkeppeler.deepcharter.ore;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * Every ore the mod adds. Value and reference mass are the original game's (dollars, and ore mass in its own unit);
 * {@link #mass()} is the mass in pod units. Cicatrium is the catalyst, and its numbers are invented.
 */
public enum OreType {
	IRONIUM("ironium", 30, 1),
	BRONZIUM("bronzium", 60, 1),
	SILVERIUM("silverium", 100, 1),
	GOLDIUM("goldium", 250, 2),
	PLATINIUM("platinium", 750, 3),
	EINSTEINIUM("einsteinium", 2000, 4),
	CICATRIUM("cicatrium", 1500, 2);

	private final String name;
	private final int value;
	private final int referenceMass;

	OreType(String name, int value, int referenceMass) {
		this.name = name;
		this.value = value;
		this.referenceMass = referenceMass;
	}

	/** What a terminal pays for one, in dollars. */
	public int value() {
		return value;
	}

	/** The original's mass for one, before scaling to pod units. */
	public int referenceMass() {
		return referenceMass;
	}

	/** Mass of one in pod units, the unit of engine power: what it adds to a pod's cargo mass and to a carrier's load. */
	public float mass() {
		return referenceMass * OreTuning.DEFAULT.massScale();
	}

	/** The id of the ore item. */
	public Identifier itemId() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, name);
	}

	/** The id of the ore block, which the drill turns into the ore item. */
	public Identifier blockId() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, name + "_ore");
	}
}
