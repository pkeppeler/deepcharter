package io.github.pkeppeler.deepcharter.creature;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import io.github.pkeppeler.deepcharter.DeepCharter;

public final class CreatureRegistry {
	private static final ResourceKey<EntityType<?>> LAMPLESS_FIGURE_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "lampless_figure"));

	/** Placeholder until the creatures session (#13). Not saved, so there is nothing to version. */
	public static final EntityType<LamplessFigure> LAMPLESS_FIGURE = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			LAMPLESS_FIGURE_KEY,
			EntityType.Builder.<LamplessFigure>of(LamplessFigure::new, MobCategory.MISC)
					.sized(0.6f, 1.95f)
					.noSave()
					.fireImmune()
					.clientTrackingRange(8)
					.build(LAMPLESS_FIGURE_KEY));

	private CreatureRegistry() {
	}

	public static void register() {
		LamplessFigure.init();
		FabricDefaultAttributeRegistry.register(LAMPLESS_FIGURE, LamplessFigure.attributes());
	}
}
