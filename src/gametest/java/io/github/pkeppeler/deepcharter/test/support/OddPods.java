package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Two chassis of odd sizes, registered by the test mod only (#399). {@link #CHASSIS} is 3.9 wide and 2.9 tall, so a 4-wide, 3-tall bore,
 * neither the Mole's 2 x 2 nor the Prospector's 3 x 3. {@link #TALL} is the other way round. The tests that must hold for any chassis run on them. It is not in
 * {@link Chassis#all()}, which is the chassis the game ships (they have a look, a price and a handbook entry), so no asset test asks for
 * its files and no client draws it. A pod type can only be registered while mods initialise, which is why this is an entrypoint.
 */
public final class OddPods implements ModInitializer {
	public static final Chassis CHASSIS = new Chassis("odd", 1, 2, 3.9f, 2.9f);
	public static final EntityType<PodEntity> TYPE = PodRegistry.register(Identifier.fromNamespaceAndPath("deepcharter_test", "odd_pod"), CHASSIS, List.of(new Vec3(0, 0.5, 0)));
	/** Tall and narrow, 1.9 wide and 3.9 tall: a 2-wide, 4-tall bore, the shape a taller Mole would have. */
	public static final Chassis TALL = new Chassis("tall", 1, 2, 1.9f, 3.9f);
	public static final EntityType<PodEntity> TALL_TYPE = PodRegistry.register(Identifier.fromNamespaceAndPath("deepcharter_test", "tall_pod"), TALL, List.of(new Vec3(0, 0.5, 0)));

	@Override
	public void onInitialize() {
		// The registration above runs when the class loads.
	}
}
