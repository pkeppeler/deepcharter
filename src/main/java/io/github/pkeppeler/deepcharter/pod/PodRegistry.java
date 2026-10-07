package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import io.github.pkeppeler.deepcharter.DeepCharter;

public final class PodRegistry {
	private static final ResourceKey<EntityType<?>> POD_KEY =
			ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod"));

	/**
	 * The pod. The hitbox is the Mole's. A rider's feet sit 0.9 above the pod's, at its middle
	 * and above its hull. Position updates go out every tick, because a pod moves fast.
	 */
	public static final EntityType<PodEntity> POD = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			POD_KEY,
			EntityType.Builder.<PodEntity>of(PodEntity::new, MobCategory.MISC)
					.sized(Chassis.MOLE.width(), Chassis.MOLE.height())
					.passengerAttachments(0.9f)
					.clientTrackingRange(10)
					.updateInterval(1)
					.build(POD_KEY));

	private PodRegistry() {
	}

	public static void register() {
		// Touching this class registers the entity type. Load the data class too, so that the
		// synced data ids are assigned at startup, the same way on both sides.
		PodData.init();
	}
}
