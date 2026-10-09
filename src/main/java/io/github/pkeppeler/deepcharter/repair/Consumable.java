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
	// Layer 1 tool: under one stock layer-1 run, the cheap way past a 10 L tank.
	RESERVE_FUEL_TANK("reserve_fuel_tank", 100),
	// Layer 2 tool: under one layer-2 run, and above the station's price for the same 30 HP.
	HULL_NANOBOTS("hull_nanobots", 340),
	// Layer 1 tool: under one stock layer-1 run.
	DYNAMITE("dynamite", 100),
	// Layer 2 tool: under one layer-2 run, and above the dynamite it outdoes.
	PLASTIC_EXPLOSIVES("plastic_explosives", 300),
	// SPEC section 11: an expensive escape, about 2 layer-2 runs; the cargo drop is the rest of the cost.
	QUANTUM_TELEPORTER("quantum_teleporter", 690),
	// SPEC section 11: a layer-3 item measured as about 4 layer-2 runs; the cargo drop is the rest of the cost.
	MATTER_TRANSMITTER("matter_transmitter", 1_390);

	private final Identifier itemId;
	private final long price;

	Consumable(String path, long price) {
		this.itemId = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
		this.price = price;
	}

	public Identifier itemId() {
		return itemId;
	}

	/** Dollars, taken from the charter's account. */
	public long price() {
		return price;
	}

	public static Optional<Consumable> byItemId(Identifier id) {
		return Arrays.stream(values()).filter(consumable -> consumable.itemId.equals(id)).findFirst();
	}
}
