package io.github.pkeppeler.deepcharter.hangar;

import java.util.List;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The four parts that repair the founding Mole, crafted from iron, copper and redstone (recipes in
 * {@code data/deepcharter/recipe}). Each one goes into the hangar console, in any order.
 */
public final class HangarParts {
	public static final Item TREAD_ASSEMBLY = part("tread_assembly");
	public static final Item ROTOR_HUB = part("rotor_hub");
	public static final Item DRILL_HEAD = part("drill_head");
	public static final Item FUEL_INJECTOR = part("fuel_injector");

	public static final List<Item> ALL = List.of(TREAD_ASSEMBLY, ROTOR_HUB, DRILL_HEAD, FUEL_INJECTOR);

	private HangarParts() {
	}

	/** Loads the class, which registers the items. */
	public static void register() {
	}

	private static Item part(String path) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key)));
	}
}
