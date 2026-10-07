package io.github.pkeppeler.deepcharter.upgrade;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.pod.Serials;

/** Registers one part item for each {@link ComponentTrack} and the {@link PartLabel} data component they carry. */
public final class ComponentItems {
	/** The label of a part stack: tier, charter and serial. Synced, so a client's tooltip can show it. */
	public static final DataComponentType<PartLabel> LABEL = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "part_label"),
			DataComponentType.<PartLabel>builder().persistent(PartLabel.CODEC).networkSynchronized(PartLabel.STREAM_CODEC).build());

	private static final Map<ComponentTrack, ComponentItem> ITEMS = new EnumMap<>(ComponentTrack.class);

	static {
		for (ComponentTrack track : ComponentTrack.values()) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, track.itemId());
			ITEMS.put(track, Registry.register(BuiltInRegistries.ITEM, key,
					new ComponentItem(track, new Item.Properties().setId(key).stacksTo(1))));
		}
	}

	private ComponentItems() {
	}

	/** Loads the class, which registers everything. */
	public static void register() {
	}

	public static ComponentItem item(ComponentTrack track) {
		return ITEMS.get(track);
	}

	/** A new part: the next serial of its track, stamped for {@code charter}. Server thread. */
	public static ItemStack mint(MinecraftServer server, ComponentTrack track, int tier, CharterId charter) {
		track.requirePartTier(tier);
		String serial = Serials.get(server).next(track.id().toUpperCase(Locale.ROOT));
		ItemStack stack = new ItemStack(item(track));
		stack.set(LABEL, new PartLabel(tier, charter, serial));
		return stack;
	}

	/** The track a stack is a part of, if it is one of ours. */
	public static Optional<ComponentTrack> trackOf(ItemStack stack) {
		return stack.getItem() instanceof ComponentItem part ? Optional.of(part.track()) : Optional.empty();
	}

	/** The label of a part stack. Empty for a stack that is not a part or has no label (for example one given by a command). */
	public static Optional<PartLabel> labelOf(ItemStack stack) {
		return trackOf(stack).flatMap(track -> Optional.ofNullable(stack.get(LABEL)));
	}
}
