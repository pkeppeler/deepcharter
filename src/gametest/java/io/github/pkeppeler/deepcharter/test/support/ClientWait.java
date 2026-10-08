package io.github.pkeppeler.deepcharter.test.support;

import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.Minecraft;

/**
 * Waits in a client GameTest for a value the server syncs. The wait is on the wall clock, not on ticks: the client and
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
	 * @throws AssertionError if the deadline passes first
	 */
	public static void until(ClientGameTestContext context, String what, Predicate<Minecraft> synced) {
		FarChunks.Deadline deadline = FarChunks.deadline();
		while (!context.computeOnClient(synced::test)) {
			if (deadline.expired()) {
				throw new AssertionError("The client did not receive " + what + " within " + FarChunks.WAIT_SECONDS + " s");
			}
			context.waitTicks(1);
		}
	}
}
