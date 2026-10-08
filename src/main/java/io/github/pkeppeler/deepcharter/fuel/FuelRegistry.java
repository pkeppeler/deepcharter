package io.github.pkeppeler.deepcharter.fuel;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** Registers biofuel and the reserve tank. The pump itself is a terminal, registered by the terminal feature. */
public final class FuelRegistry {
	/** Fuel: its litres are in {@code data/deepcharter/pod_fuel/biofuel.json} and it is in the {@code pod_fuel} tag. */
	public static final Item BIOFUEL = item("biofuel", 64);
	/** Used on a pod, it fits a {@link ReserveTank}. */
	public static final Item RESERVE_TANK = item("reserve_tank", 16);

	private FuelRegistry() {
	}

	/** Loads the class, which registers the items. */
	public static void register() {
	}

	private static Item item(String path, int stackSize) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(stackSize)));
	}
}
