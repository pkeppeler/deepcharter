package io.github.pkeppeler.deepcharter.handbook;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.event.registry.DynamicRegistries;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The handbook's chapters. They are a data-pack registry, one JSON file per chapter, and the server sends it to every client, so
 * the screen reads the same chapters the server completes directives for.
 *
 * <p>Every directive id must be defined by one chapter only. {@link #validate} checks that at server start and fails loud; the
 * tick, join and gameplay paths use {@link #directivesOrEmpty}, which never throws. The registry is a world registry: the server
 * reads it once at start, and {@code /reload} does not re-read it (see ADR 0013).
 */
public final class HandbookChapters {
	public static final ResourceKey<Registry<HandbookChapter>> KEY =
			ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook_chapter"));

	/** The directives of one loaded registry, or what is wrong with it. */
	private record Index(Object registry, Set<Identifier> directives, String problem) {
	}

	private static Index cached;

	private HandbookChapters() {
	}

	/** Registers the chapter registry. Call before registries load. */
	public static void register() {
		DynamicRegistries.registerSynced(KEY, HandbookChapter.CODEC);
	}

	/** The server's chapters in handbook order: by {@code order}, then by id. */
	public static List<Holder.Reference<HandbookChapter>> all(MinecraftServer server) {
		return all(server.registryAccess());
	}

	/** The chapters of {@code registries} in handbook order. The client calls this with the registries the server synced. */
	public static List<Holder.Reference<HandbookChapter>> all(HolderLookup.Provider registries) {
		return registries.lookupOrThrow(KEY).listElements()
				.sorted(Comparator.<Holder.Reference<HandbookChapter>>comparingInt(chapter -> chapter.value().order())
						.thenComparing(chapter -> chapter.key().identifier()))
				.toList();
	}

	/**
	 * Every directive id of every chapter, in handbook order.
	 *
	 * @throws IllegalStateException if two chapters define the same directive
	 */
	public static Set<Identifier> directives(MinecraftServer server) {
		Index index = index(server);
		if (index.problem() != null) {
			throw new IllegalStateException(index.problem());
		}
		return index.directives();
	}

	/**
	 * The directive ids, or none when two chapters define the same one (logged once when the registry is first read). For code on a
	 * tick, join, sync or gameplay path, which must not throw.
	 */
	public static Set<Identifier> directivesOrEmpty(MinecraftServer server) {
		return index(server).directives();
	}

	/**
	 * Fails loud if two chapters define the same directive. Runs when the server starts.
	 *
	 * @throws IllegalStateException naming the directive and both chapters
	 */
	public static void validate(MinecraftServer server) {
		directives(server);
	}

	/**
	 * Every directive id of {@code chapters}, in iteration order.
	 *
	 * @throws IllegalStateException naming the directive and both chapters, if two chapters define the same one
	 */
	public static Set<Identifier> collectDirectives(Map<Identifier, HandbookChapter> chapters) {
		Map<Identifier, Identifier> owners = new HashMap<>();
		Set<Identifier> directives = new LinkedHashSet<>();
		for (Map.Entry<Identifier, HandbookChapter> chapter : chapters.entrySet()) {
			for (HandbookChapter.Entry entry : chapter.getValue().directives()) {
				Identifier previous = owners.put(entry.id(), chapter.getKey());
				if (previous != null) {
					throw new IllegalStateException("handbook directive " + entry.id() + " is defined by two chapters, " + previous
							+ " and " + chapter.getKey());
				}
				directives.add(entry.id());
			}
		}
		return Collections.unmodifiableSet(directives);
	}

	private static Index index(MinecraftServer server) {
		Object registry = server.registryAccess().lookupOrThrow(KEY);
		Index index = cached;
		if (index != null && index.registry() == registry) {
			return index;
		}
		Map<Identifier, HandbookChapter> chapters = new LinkedHashMap<>();
		all(server).forEach(chapter -> chapters.put(chapter.key().identifier(), chapter.value()));
		try {
			index = new Index(registry, collectDirectives(chapters), null);
		} catch (IllegalStateException problem) {
			DeepCharter.LOGGER.error("{}", problem.getMessage());
			index = new Index(registry, Set.of(), problem.getMessage());
		}
		cached = index;
		return index;
	}
}
