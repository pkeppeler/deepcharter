package io.github.pkeppeler.deepcharter.repair;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Registers the six consumables and the repair station's actions. */
public final class RepairRegistry {
	private static final Map<Consumable, Item> ITEMS = new EnumMap<>(Consumable.class);

	static {
		for (Consumable consumable : Consumable.values()) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, consumable.itemId());
			ITEMS.put(consumable, Registry.register(BuiltInRegistries.ITEM, key,
					new ConsumableItem(consumable, new Item.Properties().setId(key).stacksTo(16))));
		}
	}

	private RepairRegistry() {
	}

	public static void register() {
		RepairStation.register();
	}

	public static Item item(Consumable consumable) {
		return ITEMS.get(consumable);
	}
}
