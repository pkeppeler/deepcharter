package io.github.pkeppeler.deepcharter.pod;

import java.util.Collection;
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

	/** Seats are Java, not look data, because the server places passengers and a dedicated server has no models. Offsets: {@code PodSeatsClientTest} and ADR 0040. */
	private static final List<Vec3> MOLE_SEATS = List.of(new Vec3(0, 6 / 16.0, -5 / 16.0));
	private static final List<Vec3> PROSPECTOR_SEATS = List.of(new Vec3(0, 8 / 16.0, -3.5 / 16.0), new Vec3(0, 8 / 16.0, -14.5 / 16.0));

	// The rider sits inside the cab. Updates every tick because pods move fast.
	public static final EntityType<PodEntity> POD = register(id("pod"), Chassis.MOLE, MOLE_SEATS);
	// The pilot sits ahead of the navigator, in tandem.
	public static final EntityType<PodEntity> PROSPECTOR = register(id("prospector"), Chassis.PROSPECTOR, PROSPECTOR_SEATS);

	/** Used on a pod, it fits a tow cable from the pod the player rides, or takes one off (see {@link PodTowing}). */
	public static final Item TOW_CABLE = item("tow_cable");

	/** The spark along a tow cable (see {@link PodTowing}). Its look is the resource files {@code particles/tow_cable.json} and its texture. */
	public static final SimpleParticleType TOW_CABLE_PARTICLE = Registry.register(BuiltInRegistries.PARTICLE_TYPE,
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "tow_cable"), FabricParticleTypes.simple());

	private PodRegistry() {
	}

	/**
	 * Registers a pod entity type for {@code chassis}, with one seat offset for each of its seats, the pilot's first (rider offsets from the
	 * pod's feet: they depend on the model, so they are per chassis and cannot be derived). The two chassis the game ships are registered
	 * above; this is public only so that a test mod can register an odd-sized one (see {@code OddPods} in the gametest source set) before
	 * the registry freezes.
	 */
	public static EntityType<PodEntity> register(Identifier id, Chassis chassis, List<Vec3> seats) {
		if (seats.size() != chassis.seats()) {
			throw new IllegalArgumentException("the chassis " + chassis.id() + " has " + chassis.seats() + " seats and the seat offsets " + seats);
		}
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
		EntityType<PodEntity> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
				EntityType.Builder.<PodEntity>of(PodEntity::new, MobCategory.MISC)
						.sized(chassis.width(), chassis.height())
						.passengerAttachments(seats.toArray(Vec3[]::new))
						.fireImmune()
						.clientTrackingRange(10)
						.updateInterval(1)
						.build(key));
		CHASSIS.put(type, chassis);
		return type;
	}

	/** Every chassis registered, shipped or not. */
	public static Collection<Chassis> chassis() {
		return CHASSIS.values();
	}

	/** The chassis of the pods of {@code type}; a type that is no pod's throws. */
	public static Chassis chassisOf(EntityType<?> type) {
		Chassis chassis = CHASSIS.get(type);
		if (chassis == null) {
			throw new IllegalArgumentException("not a pod entity type: " + type);
		}
		return chassis;
	}

	/** The chassis registered with the id {@code id}, shipped or not; an id that is none is a bug, so it throws. */
	public static Chassis chassisById(String id) {
		for (Chassis chassis : CHASSIS.values()) {
			if (chassis.id().equals(id)) {
				return chassis;
			}
		}
		throw new IllegalArgumentException("unknown pod chassis: " + id);
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

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
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
