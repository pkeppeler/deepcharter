package io.github.pkeppeler.deepcharter.pod;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The items that refuel a pod, defined in data: an item is fuel when it is in the {@link #TAG} tag, and the litres
 * it gives come from the table, one JSON file for each item. The file's path is the item's id:
 * {@code data/minecraft/pod_fuel/coal.json} holds {@code {"litres": 2.0}} for {@code minecraft:coal}. A datapack
 * adds a fuel by adding the item to the tag and a file to the table, and replaces one with a file of the same path,
 * so the pack order decides, as it does for any data file.
 *
 * <p>The table is server-only: it is loaded with the server's datapacks and never sent to a client. Client code
 * decides from the synced tag ({@code stack.is(TAG)}) alone and never calls {@link #litresOf}.
 *
 * <p>A bad datapack never stops the server. A file that does not parse, or names no item, is logged at ERROR and
 * skipped. A tagged item with no litres entry is logged at ERROR, at server start and after each reload. In every
 * case that item is not fuel.
 */
public final class PodFuelItems {
	public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_fuel"));
	private static final String DIRECTORY = "pod_fuel";
	private static final Codec<Float> LITRES = ExtraCodecs.POSITIVE_FLOAT.fieldOf("litres").codec();

	/** Replaced whole on each reload, so a reader never sees half a table. */
	private static volatile Map<Item, Float> table = Map.of();

	private PodFuelItems() {
	}

	/** Loads the table with the datapacks and checks it against the tag when the server has started. */
	static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(
				Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, DIRECTORY), new Loader());
		ServerLifecycleEvents.SERVER_STARTED.register(server -> checkTableCoversTag());
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) {
				checkTableCoversTag();
			}
		});
	}

	/** Server only. The litres the stack gives when used on a pod, or empty when it is not fuel. */
	public static OptionalDouble litresOf(ItemStack stack) {
		Float litres = stack.is(TAG) ? table.get(stack.getItem()) : null;
		return litres == null ? OptionalDouble.empty() : OptionalDouble.of(litres);
	}

	/** Logs an ERROR for each of {@code fuelItems} with no litres entry, and returns those items: they are not fuel. */
	public static List<Item> checkLitresFor(Collection<Item> fuelItems) {
		List<Item> missing = new ArrayList<>();
		for (Item item : fuelItems) {
			if (!table.containsKey(item)) {
				missing.add(item);
				DeepCharter.LOGGER.error("Item {} is in the #{} tag but has no litres entry, so it is not pod fuel: add data/{}/{}/{}.json",
						BuiltInRegistries.ITEM.getKey(item), TAG.location(), BuiltInRegistries.ITEM.getKey(item).getNamespace(),
						DIRECTORY, BuiltInRegistries.ITEM.getKey(item).getPath());
			}
		}
		return missing;
	}

	private static void checkTableCoversTag() {
		List<Item> fuelItems = new ArrayList<>();
		for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(TAG)) {
			fuelItems.add(holder.value());
		}
		checkLitresFor(fuelItems);
	}

	private static final class Loader extends SimplePreparableReloadListener<Map<Item, Float>> {
		private static final FileToIdConverter FILES = FileToIdConverter.json(DIRECTORY);

		@Override
		protected Map<Item, Float> prepare(ResourceManager manager, ProfilerFiller profiler) {
			Map<Item, Float> loaded = new HashMap<>();
			// One resource for each path: the top pack's, so a later pack replaces an earlier one.
			FILES.listMatchingResources(manager).forEach((file, resource) -> {
				Identifier id = FILES.fileToId(file);
				Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
				if (item.isEmpty()) {
					DeepCharter.LOGGER.error("Pod fuel file {} names no item {}: skipped", file, id);
					return;
				}
				litres(file, resource).ifPresent(litres -> loaded.put(item.get(), litres));
			});
			return loaded;
		}

		@Override
		protected void apply(Map<Item, Float> loaded, ResourceManager manager, ProfilerFiller profiler) {
			table = Map.copyOf(loaded);
		}

		private static Optional<Float> litres(Identifier file, Resource resource) {
			try (Reader reader = resource.openAsReader()) {
				JsonElement json = JsonParser.parseReader(reader);
				return LITRES.parse(JsonOps.INSTANCE, json).resultOrPartial(
						error -> DeepCharter.LOGGER.error("Pod fuel file {} is not valid, skipped: {}", file, error));
			} catch (IOException | RuntimeException e) {
				DeepCharter.LOGGER.error("Pod fuel file {} could not be read, skipped", file, e);
				return Optional.empty();
			}
		}
	}
}
