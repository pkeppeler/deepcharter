package io.github.pkeppeler.deepcharter.test.support;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.gamerules.GameRules;

/** Holds an evidence world still for stills: the clocks, the weather, spawning and particles, and the visual sky's phase. */
public final class EvidenceWorld {
	/**
	 * Where the visual sky stands on its own clock, {@code deepcharter:sky} (its timeline swings over 288000 ticks, which is hours of
	 * real time, so a scenario pins it): at its brightest dusk, half way to night, and at its darkest.
	 */
	public static final long SKY_BRIGHTEST = 0;
	public static final long SKY_HALFWAY = 72_000;
	public static final long SKY_DARKEST = 144_000;
	private static final ResourceKey<WorldClock> SKY = ResourceKey.create(Registries.WORLD_CLOCK, Identifier.fromNamespaceAndPath("deepcharter", "sky"));

	private EvidenceWorld() {
	}

	/**
	 * Pins everything that would make two runs of one commit differ: the gameplay clock stops at noon, the sky clock at its
	 * brightest dusk, the weather is clear and stays so, nothing grows or burns by random tick, no mob spawns, particles are at their
	 * minimum and the view does not bob.
	 */
	public static void pin(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		singleplayer.getServer().runOnServer(server -> {
			GameRules rules = server.getGameRules();
			rules.set(GameRules.ADVANCE_TIME, false, server);
			rules.set(GameRules.ADVANCE_WEATHER, false, server);
			rules.set(GameRules.SPAWN_MOBS, false, server);
			rules.set(GameRules.SPAWN_MONSTERS, false, server);
			rules.set(GameRules.SPAWN_PATROLS, false, server);
			rules.set(GameRules.SPAWN_PHANTOMS, false, server);
			rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
			rules.set(GameRules.SPAWN_WARDENS, false, server);
			rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
			command(server, "weather clear");
			command(server, "time set noon");
		});
		skyPhase(context, singleplayer, SKY_BRIGHTEST);
		context.runOnClient(client -> {
			client.options.particles().set(ParticleStatus.MINIMAL);
			client.options.bobView().set(false);
		});
	}

	/**
	 * Pins the visual sky: the sky clock stops at {@code ticks} of its timeline, and the call returns once the client reads that
	 * phase back, so no still is taken with the sky of the phase before (the overworld's fog colour is the dusk brown). The sky
	 * follows this clock, not the gameplay clock that {@code time set} moves, so a still that wants another sky sets both.
	 */
	public static void skyPhase(ClientGameTestContext context, TestSingleplayerContext singleplayer, long ticks) {
		singleplayer.getServer().runOnServer(server -> {
			var sky = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(SKY);
			server.clockManager().setPaused(sky, true);
			server.clockManager().setTotalTicks(sky, ticks);
		});
		ClientWait.until(context, "the client to read the sky clock at " + ticks, client -> skyOnClient(client).totalTicks() == ticks && skyOnClient(client).isPaused(),
				client -> "the sky clock at " + skyOnClient(client).totalTicks());
	}

	private static ClockInstance skyOnClient(Minecraft client) {
		return client.level.clockManager().getInstance(client.level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(SKY));
	}

	private static void command(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
	}
}
