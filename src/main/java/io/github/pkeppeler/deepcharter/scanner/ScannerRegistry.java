package io.github.pkeppeler.deepcharter.scanner;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** Registers the scanner unit, the crafted item that becomes the tier 1 scanner. ScanSlice is plain shared code and its ore tag is data. */
public final class ScannerRegistry {
	/** The tier 1 scanner as the player makes it (SPEC section 7). Using it on the player's pod fits the part; see {@link ScannerFitting}. */
	public static final Item SCANNER_UNIT = unit();

	private ScannerRegistry() {
	}

	/** Loads the class, which registers the item. */
	public static void register() {
	}

	private static Item unit() {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "scanner_unit"));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
	}
}
