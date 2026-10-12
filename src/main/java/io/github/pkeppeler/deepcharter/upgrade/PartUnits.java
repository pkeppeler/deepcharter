package io.github.pkeppeler.deepcharter.upgrade;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;

/**
 * The crafted units: one item for each track, since tier 1 of every track is crafted, which {@link PartFitting}
 * turns into a tier 1 part when it is used on the player's pod (ADR 0048, ADR 0053). The scanner's unit is {@link ScannerRegistry#SCANNER_UNIT}
 * and the others are registered here as {@code <track>_unit}. A unit carries no label: its serial and charter are stamped when it is fitted.
 */
public final class PartUnits {
	private static final Map<ComponentTrack, Item> UNITS = new EnumMap<>(ComponentTrack.class);

	static {
		for (ComponentTrack track : ComponentTrack.values()) {
			if (track == ComponentTrack.SCANNER) {
				UNITS.put(track, ScannerRegistry.SCANNER_UNIT);
			} else {
				ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, track.id() + "_unit"));
				UNITS.put(track, Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1))));
			}
		}
	}

	private PartUnits() {
	}

	/** Loads the class, which registers the units. */
	public static void register() {
	}

	/** The track a stack is the crafted unit of, if it is one. */
	public static Optional<ComponentTrack> trackOf(ItemStack stack) {
		return UNITS.entrySet().stream().filter(entry -> stack.is(entry.getValue())).map(Map.Entry::getKey).findFirst();
	}
}
