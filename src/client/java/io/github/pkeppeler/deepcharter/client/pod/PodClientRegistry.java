package io.github.pkeppeler.deepcharter.client.pod;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

public final class PodClientRegistry {
	private PodClientRegistry() {
	}

	public static void register() {
		EntityRendererRegistry.register(PodRegistry.POD,
				context -> new PodRenderer(context, Chassis.MOLE, Blocks.RAW_COPPER_BLOCK.defaultBlockState()));
		EntityRendererRegistry.register(PodRegistry.PROSPECTOR,
				context -> new PodRenderer(context, Chassis.PROSPECTOR, Blocks.IRON_BLOCK.defaultBlockState()));
	}
}
