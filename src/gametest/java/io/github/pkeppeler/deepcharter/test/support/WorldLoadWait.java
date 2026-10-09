package io.github.pkeppeler.deepcharter.test.support;

import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Wall-clock world-load wait that the test mixin swaps in for Fabric's 1200-tick one (issue 277). */
public final class WorldLoadWait {
	/** Longer than a chunk wait: a world load on a loaded Mac is slower. */
	public static final int WORLD_LOAD_SECONDS = 300;
	private static final AtomicInteger WAITS = new AtomicInteger();

	private WorldLoadWait() {
	}

	/** How many world loads this wait has served in this JVM; a test reads it to see that the mixin replaced Fabric's wait. */
	public static int waits() {
		return WAITS.get();
	}

	/** Steps through the loading screens until the level is up; fails after {@link #WORLD_LOAD_SECONDS} naming the stage. */
	public static void await(ClientGameTestContext context) {
		WAITS.incrementAndGet();
		FarChunks.Deadline deadline = FarChunks.deadline(WORLD_LOAD_SECONDS);
		while (true) {
			if (context.computeOnClient(client -> isExperimentalWarning(client.gui.screen()))) {
				context.clickScreenButton("gui.yes");
			}
			if (context.computeOnClient(client -> client.gui.screen() instanceof BackupConfirmScreen)) {
				context.clickScreenButton("selectWorld.backupJoinSkipButton");
			}
			if (context.computeOnClient(WorldLoadWait::finished)) {
				return;
			}
			if (deadline.expired()) {
				String seen = context.computeOnClient(client -> stage(client) + "; the client had " + ClientWait.describe(client));
				throw new AssertionError("Timeout loading world: stopped after " + WORLD_LOAD_SECONDS + " s in " + seen);
			}
			try {
				Thread.sleep(FarChunks.POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while loading the world", e);
			}
			context.waitTick();
		}
	}

	/** The stage a stuck load is in, from what the client shows. */
	public static String stage(Minecraft client) {
		if (client.level == null) {
			return "no level yet (still connecting, or disconnected)";
		}
		if (client.gui.screen() instanceof LevelLoadingScreen) {
			return "the world load stage (the level loading screen is still up, so the spawn chunks have not rendered)";
		}
		return "an unexpected stage (the level exists and the loading screen is gone, but the wait did not finish)";
	}

	private static boolean finished(Minecraft client) {
		return client.level != null && !(client.gui.screen() instanceof LevelLoadingScreen);
	}

	private static boolean isExperimentalWarning(Screen screen) {
		return screen instanceof ConfirmScreen
				&& screen.getTitle().getContents() instanceof TranslatableContents translatable
				&& "selectWorld.warning.experimental.title".equals(translatable.getKey());
	}
}
