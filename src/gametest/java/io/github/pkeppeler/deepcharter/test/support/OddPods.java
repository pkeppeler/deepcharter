package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * A chassis of an odd size, registered by the test mod only (#399): 3.9 wide and 2.9 tall, so a 4-wide, 3-tall bore, neither the Mole's
 * 2 x 2 nor the Prospector's 3 x 3, and not square on the vertical. The tests that must hold for any chassis run on it. It is not in
 * {@link Chassis#all()}, which is the chassis the game ships (they have a look, a price and a handbook entry), so no asset test asks for
 * its files and no client draws it. A pod type can only be registered while mods initialise, which is why this is an entrypoint.
 */
public final class OddPods implements ModInitializer {
	public static final Chassis CHASSIS = new Chassis("odd", 1, 2, 3.9f, 2.9f);
	public static final EntityType<PodEntity> TYPE = PodRegistry.register(Identifier.fromNamespaceAndPath("deepcharter_test", "odd_pod"), CHASSIS, new Vec3(0, 0.9, 0));

	@Override
	public void onInitialize() {
		// The registration above runs when the class loads.
	}
}
