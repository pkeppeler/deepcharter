package io.github.pkeppeler.deepcharter.pod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
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
	/** Declared first: each registration below adds its chassis. */
	private static final Map<EntityType<?>, Chassis> CHASSIS = new HashMap<>();

	/**
	 * Where each chassis seats its riders, pilot first: the point the rider sits on (the bottom of the seat), in blocks from the pod's feet,
	 * +z being the way the pod faces. They are not a look file, though the model fixes them: the server places passengers from the entity
	 * type, and a dedicated server has no models. The Mole's seat is 6 pixels up and 5 back of its nose (the cab of mole.geo.json), and the
	 * Prospector's two are 8 pixels up, 3.5 and 14.5 back (#382); {@code PodSeatsClientTest} holds each to its cab.
	 */
	private static final Map<Chassis, List<Vec3>> SEATS = Map.of(
			Chassis.MOLE, List.of(new Vec3(0, 6 / 16.0, -5 / 16.0)),
			Chassis.PROSPECTOR, List.of(new Vec3(0, 8 / 16.0, -3.5 / 16.0), new Vec3(0, 8 / 16.0, -14.5 / 16.0)));

	// The rider sits inside the cab. Updates every tick because pods move fast.
	public static final EntityType<PodEntity> POD = register("pod", Chassis.MOLE);
	// The pilot sits ahead of the navigator, in tandem.
	public static final EntityType<PodEntity> PROSPECTOR = register("prospector", Chassis.PROSPECTOR);

	/** Used on a pod, it fits a tow cable from the pod the player rides, or takes one off (see {@link PodTowing}). */
	public static final Item TOW_CABLE = item("tow_cable");

	/** The spark along a tow cable (see {@link PodTowing}). Its look is the resource files {@code particles/tow_cable.json} and its texture. */
	public static final SimpleParticleType TOW_CABLE_PARTICLE = Registry.register(BuiltInRegistries.PARTICLE_TYPE,
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "tow_cable"), FabricParticleTypes.simple());

	private PodRegistry() {
	}

	private static EntityType<PodEntity> register(String path, Chassis chassis) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
		EntityType<PodEntity> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
				EntityType.Builder.<PodEntity>of(PodEntity::new, MobCategory.MISC)
						.sized(chassis.width(), chassis.height())
						.passengerAttachments(seatsOf(chassis).toArray(Vec3[]::new))
						.fireImmune()
						.clientTrackingRange(10)
						.updateInterval(1)
						.build(key));
		CHASSIS.put(type, chassis);
		return type;
	}

	/** The seats of {@code chassis}, pilot first: where a rider sits, in blocks from the pod's feet (+z is forward). */
	public static List<Vec3> seatsOf(Chassis chassis) {
		List<Vec3> seats = SEATS.get(chassis);
		if (seats == null || seats.size() != chassis.seats()) {
			throw new IllegalStateException("the chassis " + chassis.id() + " has " + chassis.seats() + " seats and the seat offsets " + seats);
		}
		return seats;
	}

	/** The chassis of the pods of {@code type}; a type that is no pod's throws. */
	public static Chassis chassisOf(EntityType<?> type) {
		Chassis chassis = CHASSIS.get(type);
		if (chassis == null) {
			throw new IllegalArgumentException("not a pod entity type: " + type);
		}
		return chassis;
	}

	/** The entity type of the pods of {@code chassis}; a chassis with none is a bug, so it throws. */
	public static EntityType<PodEntity> typeOf(Chassis chassis) {
		for (Map.Entry<EntityType<?>, Chassis> entry : CHASSIS.entrySet()) {
			if (entry.getValue().equals(chassis)) {
				@SuppressWarnings("unchecked")
				EntityType<PodEntity> type = (EntityType<PodEntity>) entry.getKey();
				return type;
			}
		}
		throw new IllegalArgumentException("no pod entity type for the chassis " + chassis.id());
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
