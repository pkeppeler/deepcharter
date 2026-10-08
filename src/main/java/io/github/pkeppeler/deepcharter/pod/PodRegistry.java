package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;

public final class PodRegistry {
	// Seat 0.9 up: the rider sits on the hull. Updates every tick because pods move fast.
	public static final EntityType<PodEntity> POD = register("pod", Chassis.MOLE, new Vec3(0, 0.9, 0));
	// The pilot sits ahead of the navigator, on the same 0.9 hull.
	public static final EntityType<PodEntity> PROSPECTOR = register("prospector", Chassis.PROSPECTOR, new Vec3(0, 0.9, 0.7), new Vec3(0, 0.9, -0.7));

	/** Used on a pod, it fits a tow cable from the pod the player rides, or takes one off (see {@link PodTowing}). */
	public static final Item TOW_CABLE = item("tow_cable");

	private PodRegistry() {
	}

	private static EntityType<PodEntity> register(String path, Chassis chassis, Vec3... seats) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
				EntityType.Builder.<PodEntity>of(PodEntity::new, MobCategory.MISC)
						.sized(chassis.width(), chassis.height())
						.passengerAttachments(seats)
						.clientTrackingRange(10)
						.updateInterval(1)
						.build(key));
	}

	private static Item item(String path) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(1)));
	}

	public static void register() {
		// Touching this class registers the entity type. Load the data class too, so that the
		// synced data ids are assigned at startup, the same way on both sides.
		PodData.init();
	}
}
