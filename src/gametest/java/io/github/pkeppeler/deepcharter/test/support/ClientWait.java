package io.github.pkeppeler.deepcharter.test.support;

import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Waits in a client GameTest for a value the server syncs, or for any other state the test awaits. The wait is on the wall clock, not on ticks: the client and
 * server tick unthrottled, so a tick budget runs out faster the more the Mac is loaded. Pod parts, hull and cargo reach
 * the client in separate packets, so seeing one of them does not mean the others have arrived.
 */
public final class ClientWait {
	private ClientWait() {
	}

	/**
	 * Waits until {@code synced} holds on the client, for up to {@link FarChunks#WAIT_SECONDS}.
	 *
	 * @param what names the awaited state in the failure message
	 * @param actual describes what the client has, read on the client; it goes in the failure message
	 * @throws AssertionError if the deadline passes first
	 */
	public static void until(ClientGameTestContext context, String what, Predicate<Minecraft> synced, Function<Minecraft, String> actual) {
		until(context, what, () -> context.computeOnClient(synced::test), () -> context.computeOnClient(actual::apply));
	}

	/**
	 * Like {@link #until(ClientGameTestContext, String, Predicate, Function)}, with the client's level, player, vehicle and screen
	 * as the failure's description. For a wait whose condition says it all.
	 */
	public static void until(ClientGameTestContext context, String what, Predicate<Minecraft> synced) {
		until(context, what, synced, ClientWait::describe);
	}

	/** Waits until the client shows a {@code screen}, for up to {@link FarChunks#WAIT_SECONDS}. The wall-clock {@code waitForScreen}. */
	public static void screen(ClientGameTestContext context, Class<? extends Screen> screen) {
		until(context, "the " + screen.getSimpleName() + " screen", client -> screen.isInstance(client.gui.screen()));
	}

	/**
	 * Waits until {@code condition} holds, for up to {@link FarChunks#WAIT_SECONDS}. The condition runs on the test thread, so it may
	 * read server state through {@code computeOnServer}, which a {@link Predicate} on the client thread may not.
	 */
	public static void until(ClientGameTestContext context, String what, BooleanSupplier condition, Supplier<String> actual) {
		FarChunks.Deadline deadline = FarChunks.deadline();
		while (!condition.getAsBoolean()) {
			if (deadline.expired()) {
				throw new AssertionError("Timed out after " + FarChunks.WAIT_SECONDS + " s waiting for " + what + "; it had " + actual.get());
			}
			try {
				Thread.sleep(FarChunks.POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for " + what, e);
			}
			context.waitTicks(1);
		}
	}

	/** What the client is showing, for a failure message: the level, whether it has a player, what the player rides, and the open screen. */
	public static String describe(Minecraft client) {
		String level = client.level == null ? "no level" : client.level.dimension().identifier().toString();
		String player = client.player == null ? "no player"
				: "a player at " + client.player.blockPosition().toShortString() + " in " + (client.gameMode == null ? "no game mode" : client.gameMode.getPlayerMode());
		String vehicle = client.player == null || client.player.getVehicle() == null ? "no vehicle" : client.player.getVehicle().getType().toString();
		String screen = client.gui.screen() == null ? "no screen" : client.gui.screen().getClass().getSimpleName();
		return level + ", " + player + ", " + vehicle + ", " + screen;
	}
}
