package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Game rules the surface sets once, when a world is made: the spawners no biome data reaches (the wandering trader, phantoms, wardens)
 * and, as a second layer, patrols (ADR 0036). The locator bar is off too: crewmates are met on the surface and below, not found by
 * bearing. {@code spawn_monsters} stays on or the Retained stop. {@link ColonyEvents#BUILT} fires once per world, at its first start, so
 * a player who turns a rule back on keeps that choice.
 */
public final class SurfaceWorldRules {
    private SurfaceWorldRules() {}

    public static void init() {
        ColonyEvents.BUILT.register((server, colony) -> {
            server.getGameRules().set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
            server.getGameRules().set(GameRules.SPAWN_PATROLS, false, server);
            server.getGameRules().set(GameRules.SPAWN_PHANTOMS, false, server);
            server.getGameRules().set(GameRules.SPAWN_WARDENS, false, server);
            server.getGameRules().set(GameRules.LOCATOR_BAR, false, server);
        });
    }
}
