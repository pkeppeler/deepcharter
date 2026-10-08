package io.github.pkeppeler.deepcharter.repair;

import java.util.Arrays;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The six items the repair station sells. The original's debug Core Teleporter is not sold, so it is not here.
 *
 * <p>Prices follow issue 201's scale (PR 206), not the original's $2,000 to $10,000. The tools cost a quarter of a run to one run's net
 * of the layer where they start to matter; SPEC section 11 calls the two teleport items expensive, so they cost about 2 and 4 layer 2
 * runs. A stock Mole's run in layer 1 nets about $115 and a Mole with tier 2 parts in layer 2 about $397 ({@code EarlyRunModel});
 * {@code EconomyAffordabilityTest} holds each item to its band.
 */
public enum Consumable {
	RESERVE_FUEL_TANK("reserve_fuel_tank", 100, "layer 1: under one stock layer 1 run ($115), as the cheapest way past a 10 L tank"),
	HULL_NANOBOTS("hull_nanobots", 350, "layer 2: under one layer 2 run ($397), and above the $30 the station charges for the same 30 HP"),
	DYNAMITE("dynamite", 100, "layer 1: under one stock layer 1 run ($115), a tool for every dig"),
	PLASTIC_EXPLOSIVES("plastic_explosives", 300, "layer 2: under one layer 2 run ($397), and above the dynamite it outdoes"),
	QUANTUM_TELEPORTER("quantum_teleporter", 750, "layer 2: about two layer 2 runs ($397 each), an expensive emergency exit (SPEC section 11)"),
	MATTER_TRANSMITTER("matter_transmitter", 1_500, "layer-3 item, measured as 4 layer 2 runs ($397 each), the dearest emergency exit (SPEC section 11)");

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
