package io.github.pkeppeler.deepcharter.repair;

import java.util.Arrays;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The six items the repair station sells. The original's debug Core Teleporter is not sold, so it is not here.
 *
 * <p>Prices follow issue 201's scale (PR 206), not the original's $2,000 to $10,000: an item costs about one run's net of the layer
 * where it starts to matter. A stock Mole's run in layer 1 nets about $115 and a Mole with tier 2 parts in layer 2 about $397
 * ({@code EarlyRunModel}); {@code EconomyAffordabilityTest} holds each item to its target. The original's order of price is kept.
 */
public enum Consumable {
	RESERVE_FUEL_TANK("reserve_fuel_tank", 100, "layer 1: under one stock layer 1 run ($115), as the cheapest way past a 10 L tank"),
	HULL_NANOBOTS("hull_nanobots", 350, "layer 2: under one layer 2 run ($397), and above the $30 that the station charges for the same 30 HP, which is the price of mending in the field"),
	DYNAMITE("dynamite", 100, "layer 1: under one stock layer 1 run ($115), a tool for every dig"),
	PLASTIC_EXPLOSIVES("plastic_explosives", 300, "layer 2: under one layer 2 run ($397), and above the dynamite it outdoes"),
	QUANTUM_TELEPORTER("quantum_teleporter", 250, "layer 2: under one layer 2 run ($397), the price of a stranded pod's way home"),
	MATTER_TRANSMITTER("matter_transmitter", 750, "layer 3: at most two layer 2 runs ($794), the dearest item for a deep pod");

	private final Identifier itemId;
	private final long price;
	private final String rationale;

	Consumable(String path, long price, String rationale) {
		this.itemId = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
		this.price = price;
		this.rationale = rationale;
	}

	public Identifier itemId() {
		return itemId;
	}

	/** Dollars, taken from the charter's account. */
	public long price() {
		return price;
	}

	/** Why the item costs what it does, in one line. */
	public String rationale() {
		return rationale;
	}

	public static Optional<Consumable> byItemId(Identifier id) {
		return Arrays.stream(values()).filter(consumable -> consumable.itemId.equals(id)).findFirst();
	}
}
