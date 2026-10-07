package io.github.pkeppeler.deepcharter.pod;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The items that refuel a pod, defined in data: an item is fuel when it is in the {@link #TAG} tag, and the litres
 * it gives come from the table, one JSON file for each item under {@code data/<namespace>/pod_fuel/}:
 * {@code {"item": "minecraft:coal", "litres": 2.0}}. A datapack adds a fuel by adding the item to the tag and a file
 * to the table; the file's name does not matter.
 *
 * <p>Every item in the tag needs a table entry, and an item must not have two. A table that breaks either rule
 * fails the datapack reload and the server start with the item named, so a half-defined fuel is never a pod that
 * quietly refuses it.
 */
public final class PodFuelItems {
	public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_fuel"));
	private static final String DIRECTORY = "pod_fuel";

	/** One file of the table. */
	private record Entry(Item item, float litres) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(Entry::item),
				ExtraCodecs.POSITIVE_FLOAT.fieldOf("litres").forGetter(Entry::litres)).apply(instance, Entry::new));
	}

	/** Replaced whole on each reload, so a reader never sees half a table. */
	private static volatile Map<Item, Float> table = Map.of();

	private PodFuelItems() {
	}

	/** Loads the table with the datapacks and checks it against the tag when the server has started. */
	static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(
				Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, DIRECTORY), new Loader());
		ServerLifecycleEvents.SERVER_STARTED.register(server -> requireTableCoversTag());
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) {
				requireTableCoversTag();
			}
		});
	}

	/** The litres the stack gives when used on a pod, or empty when it is not fuel. */
	public static OptionalDouble litresOf(ItemStack stack) {
		Float litres = stack.is(TAG) ? table.get(stack.getItem()) : null;
		return litres == null ? OptionalDouble.empty() : OptionalDouble.of(litres);
	}

	/** Throws, naming the item, unless every one of {@code fuelItems} has a litres entry. */
	public static void requireLitresFor(Collection<Item> fuelItems) {
		for (Item item : fuelItems) {
			if (!table.containsKey(item)) {
				throw new IllegalStateException("item " + BuiltInRegistries.ITEM.getKey(item) + " is in the #" + TAG.location()
						+ " tag but has no litres entry: add a file under data/<namespace>/" + DIRECTORY + "/");
			}
		}
	}

	private static void requireTableCoversTag() {
		List<Item> fuelItems = new ArrayList<>();
		for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(TAG)) {
			fuelItems.add(holder.value());
		}
		requireLitresFor(fuelItems);
	}

	private static final class Loader extends SimpleJsonResourceReloadListener<Entry> {
		Loader() {
			super(Entry.CODEC, FileToIdConverter.json(DIRECTORY));
		}

		@Override
		protected void apply(Map<Identifier, Entry> files, ResourceManager manager, ProfilerFiller profiler) {
			Map<Item, Float> loaded = new HashMap<>();
			Map<Item, Identifier> source = new HashMap<>();
			files.forEach((file, entry) -> {
				Identifier earlier = source.put(entry.item(), file);
				if (earlier != null) {
					throw new IllegalStateException("pod fuel " + BuiltInRegistries.ITEM.getKey(entry.item())
							+ " is defined twice: by " + earlier + " and by " + file);
				}
				loaded.put(entry.item(), entry.litres());
			});
			table = Map.copyOf(loaded);
		}
	}
}
