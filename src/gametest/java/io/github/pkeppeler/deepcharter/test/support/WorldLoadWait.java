package io.github.pkeppeler.deepcharter.test.support;

import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * The wait for a world to load after {@code worldBuilder().create()} or {@code TestDedicatedServerContext.connect()}, on the wall clock.
 * Fabric's own wait, {@code ClientGameTestImpl.waitForWorldLoad}, is a loop of 1200 client ticks that fails with "Timeout loading
 * world". The client ticks unthrottled, so under load that is a few seconds of real time. {@code ClientGameTestImplMixin} replaces
 * that method with {@link #await}, which takes the same steps and polls a {@link #WORLD_LOAD_SECONDS} deadline.
 */
public final class WorldLoadWait {
	/** Wall-clock seconds that a world load or a connect may take. A world load on a loaded Mac is slower than a chunk wait, so this is longer than {@link FarChunks#WAIT_SECONDS}. */
	public static final int WORLD_LOAD_SECONDS = 300;
	private static final AtomicInteger WAITS = new AtomicInteger();

	private WorldLoadWait() {
	}

	/** How many world loads this wait has served in this JVM; a test reads it to see that the mixin replaced Fabric's wait. */
	public static int waits() {
		return WAITS.get();
	}

	/**
	 * Steps through the loading screens until the client has a level and has left the loading screen, as Fabric's own wait does:
	 * it confirms the experimental-settings warning and the backup prompt.
	 *
	 * @throws AssertionError after {@link #WORLD_LOAD_SECONDS}, naming the stage the load stopped in and what the client had
	 */
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
			return "the connect and login stage (the client has no level yet)";
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
