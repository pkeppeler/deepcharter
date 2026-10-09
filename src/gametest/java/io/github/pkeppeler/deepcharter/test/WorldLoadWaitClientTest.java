package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.WorldLoadWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #277: a world load is waited on by {@link WorldLoadWait} on the wall clock, not by Fabric's 1200-tick loop.
 * The mixin that swaps them fails the launch if it cannot apply, and this test fails if it applied but a world load did not reach it.
 */
public class WorldLoadWaitClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		int before = WorldLoadWait.waits();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			ClientWait.until(context, "the client in the world", client -> client.player != null && client.level != null);
			require(WorldLoadWait.waits() == before + 1, "the world load did not go through WorldLoadWait: Fabric's tick loop ran instead (waits " + before + " then " + WorldLoadWait.waits() + ")");
			String inLevel = context.computeOnClient(WorldLoadWait::stage);
			require(inLevel.startsWith("an unexpected stage"), "with the level up and no loading screen, the stage should be the unexpected one, was: " + inLevel);
		}
		String noLevel = context.computeOnClient(WorldLoadWait::stage);
		require(noLevel.startsWith("the connect and login stage"), "with no level, the stage should be connect and login, was: " + noLevel);
	}
}
