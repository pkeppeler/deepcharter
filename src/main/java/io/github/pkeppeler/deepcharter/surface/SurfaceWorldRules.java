package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Game rules the surface sets once, when a world is made. Vanilla's own monsters spawn nowhere (ADR 0036): the biomes list none, and
 * the spawners that ignore a biome's spawn list are turned off here, because no data reaches them. That is the wandering trader and its
 * llamas, pillager patrols, phantoms and wardens. {@code spawn_monsters} stays on: it would stop the Retained too, whose biomes
 * list them. The locator bar (the coloured dots of other players in the experience bar) is off too: crewmates are met on the surface
 * and below, not found by bearing. {@link ColonyEvents#BUILT} fires once per world, at its first start, so a player who turns a rule
 * back on keeps that choice.
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
