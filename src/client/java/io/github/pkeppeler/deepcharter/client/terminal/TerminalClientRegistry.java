package io.github.pkeppeler.deepcharter.client.terminal;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import io.github.pkeppeler.deepcharter.terminal.TerminalViewPayload;

/** Opens the terminal screen when the server sends a terminal view. */
public final class TerminalClientRegistry {
	private TerminalClientRegistry() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(TerminalViewPayload.TYPE, (payload, context) -> TerminalScreens.show(payload.view()));
	}
}
