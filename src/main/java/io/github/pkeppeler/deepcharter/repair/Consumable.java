package io.github.pkeppeler.deepcharter.repair;

import java.util.Arrays;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The six items the repair station sells, at the original's prices (original_flash_game/REFERENCE.md section 3). The
 * original's debug Core Teleporter is not sold, so it is not here.
 */
public enum Consumable {
	RESERVE_FUEL_TANK("reserve_fuel_tank", 2_000),
	HULL_NANOBOTS("hull_nanobots", 7_500),
	DYNAMITE("dynamite", 2_000),
	PLASTIC_EXPLOSIVES("plastic_explosives", 5_000),
	QUANTUM_TELEPORTER("quantum_teleporter", 2_000),
	MATTER_TRANSMITTER("matter_transmitter", 10_000);

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
