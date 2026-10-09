package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.WorldClock;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.test.support.TestClocks;

/** The sanctioned form for a server test that needs a world clock at a chosen phase: the body sees the phase, and the clock is restored after. */
public class TestClocksTest {
	private static final long PINNED = 12_345;

	@GameTest
	public void pausedPinsTheClockInTheBodyAndRestoresItAfter(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		Holder<WorldClock> sky = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
				.getOrThrow(ResourceKey.create(Registries.WORLD_CLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sky")));
		ClockInstance clock = server.clockManager().getInstance(sky);
		long ticksBefore = clock.totalTicks();
		boolean pausedBefore = clock.isPaused();

		TestClocks.paused(server, sky, PINNED, () -> {
			if (clock.totalTicks() != PINNED || !clock.isPaused()) {
				throw helper.assertionException("the clock should be paused at %s in the body, was %s (paused: %s)", PINNED, clock.totalTicks(), clock.isPaused());
			}
		});
		expectRestored(helper, clock, ticksBefore, pausedBefore);

		try {
			TestClocks.paused(server, sky, PINNED, () -> {
				throw new IllegalStateException("body failed");
			});
			throw helper.assertionException("the body's exception should reach the caller");
		} catch (IllegalStateException expected) {
			expectRestored(helper, clock, ticksBefore, pausedBefore);
		}
		helper.succeed();
	}

	private static void expectRestored(GameTestHelper helper, ClockInstance clock, long ticks, boolean paused) {
		if (clock.totalTicks() != ticks || clock.isPaused() != paused) {
			throw helper.assertionException("the clock should be back at %s ticks, paused %s, was %s, %s",
					ticks, paused, clock.totalTicks(), clock.isPaused());
		}
	}
}
