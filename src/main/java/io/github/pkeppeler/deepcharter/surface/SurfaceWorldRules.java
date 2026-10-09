package io.github.pkeppeler.deepcharter.surface;

import net.minecraft.world.level.gamerules.GameRules;

import io.github.pkeppeler.deepcharter.colony.ColonyEvents;

/**
 * Game rules the surface sets once, when a world is made. The wandering trader and its llamas are the one passive spawner that ignores
 * a biome's spawn list, so the biomes cannot turn them off. {@link ColonyEvents#BUILT} fires once per world, at its first start, so a
 * player who turns the rule back on keeps that choice.
 */
public final class SurfaceWorldRules {
	private SurfaceWorldRules() {
	}

	public static void init() {
		ColonyEvents.BUILT.register((server, colony) -> server.getGameRules().set(GameRules.SPAWN_WANDERING_TRADERS, false, server));
	}
}
