package io.github.pkeppeler.deepcharter.pod;

import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.ValueInput;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The entity-type ids our pods used to have, and the type each one is now. Vanilla drops a saved entity whose type id is not registered,
 * before any mod code runs, so a pod under a renamed or removed id is lost with its cargo. {@code EntityTypeMixin} loads a saved entity
 * under an id in {@link #RENAMES} as the new type instead (ADR 0045). To rename or remove a pod type, add one line here with its old id;
 * never delete a line.
 */
public final class PodRenames {
	/** Old id to new id. {@code retired_pod_for_test} is never registered: it lets {@code PodRenamesTest} load a real saved pod through the real table. */
	private static final Map<Identifier, Identifier> RENAMES = Map.of(
			id("retired_pod_for_test"), id("pod"));

	private PodRenames() {
	}

	public static void init() {
		validate(RENAMES);
	}

	/** Throws, naming both ids, unless every old id is unregistered and every new id is a registered pod type. */
	public static void validate(Map<Identifier, Identifier> renames) {
		renames.forEach((old, current) -> {
			String entry = "PodRenames: " + old + " -> " + current + ": ";
			if (BuiltInRegistries.ENTITY_TYPE.containsKey(old)) {
				throw new IllegalStateException(entry + "the old id is still registered, so it is not renamed");
			}
			try {
				PodRegistry.chassisOf(BuiltInRegistries.ENTITY_TYPE.getValue(current));
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException(entry + "new id is not a registered pod type", e);
			}
		});
	}

	/** The current type of the entity saved in {@code input}, or empty when its id is no renamed pod type. */
	public static Optional<EntityType<?>> current(ValueInput input) {
		return input.read("id", Identifier.CODEC).map(RENAMES::get).map(BuiltInRegistries.ENTITY_TYPE::getValue);
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
	}
}
