package io.github.pkeppeler.deepcharter.handbook;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.event.registry.DynamicRegistries;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The handbook's chapters. They are a data-pack registry, one JSON file per chapter, and the server sends it to every client, so
 * the screen reads the same chapters the server completes directives for.
 */
public final class HandbookChapters {
	public static final ResourceKey<Registry<HandbookChapter>> KEY =
			ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook_chapter"));

	private HandbookChapters() {
	}

	/** Registers the chapter registry. Call before registries load. */
	public static void register() {
		DynamicRegistries.registerSynced(KEY, HandbookChapter.CODEC);
	}

	/** The server's chapters in handbook order: by {@code order}, then by id. */
	public static List<Holder.Reference<HandbookChapter>> all(MinecraftServer server) {
		return server.registryAccess().lookupOrThrow(KEY).listElements()
				.sorted(Comparator.<Holder.Reference<HandbookChapter>>comparingInt(chapter -> chapter.value().order())
						.thenComparing(chapter -> chapter.key().identifier()))
				.toList();
	}

	/**
	 * Every directive id of every chapter.
	 *
	 * @throws IllegalStateException if two chapters define the same directive
	 */
	public static Set<Identifier> directives(MinecraftServer server) {
		Set<Identifier> directives = new LinkedHashSet<>();
		for (Holder.Reference<HandbookChapter> chapter : all(server)) {
			for (HandbookChapter.Entry entry : chapter.value().directives()) {
				if (!directives.add(entry.id())) {
					throw new IllegalStateException("directive " + entry.id() + " is defined by more than one chapter, one of them "
							+ chapter.key().identifier());
				}
			}
		}
		return directives;
	}
}
