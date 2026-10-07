package io.github.pkeppeler.deepcharter.terminal;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The parts that repair the four colony terminals, crafted from iron, copper and redstone (recipes in
 * {@code data/deepcharter/recipe}). A terminal takes two or three, and each part belongs to one terminal.
 */
public final class TerminalParts {
	public static final Item PUMP_MOTOR = part("pump_motor");
	public static final Item FUEL_VALVE = part("fuel_valve");

	public static final Item SMELTER_COIL = part("smelter_coil");
	public static final Item CRUSHER_GEAR = part("crusher_gear");
	public static final Item ORE_FEEDER = part("ore_feeder");

	public static final Item UPGRADE_BOARD = part("upgrade_board");
	public static final Item SOCKET_ARRAY = part("socket_array");

	public static final Item WELDING_ARM = part("welding_arm");
	public static final Item SERVO_UNIT = part("servo_unit");
	public static final Item REPAIR_CIRCUIT = part("repair_circuit");

	private TerminalParts() {
	}

	/** Loads the class, which registers the items. */
	public static void register() {
	}

	private static Item part(String path) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key)));
	}
}
