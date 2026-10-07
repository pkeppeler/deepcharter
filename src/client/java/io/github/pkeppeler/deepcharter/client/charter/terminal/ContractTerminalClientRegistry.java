package io.github.pkeppeler.deepcharter.client.charter.terminal;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractStatePayload;
import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;

/** Sets the screen of the contract terminal, and hands the server's state to it when it is open. */
public final class ContractTerminalClientRegistry {
	private ContractTerminalClientRegistry() {
	}

	public static void register() {
		TerminalScreens.register(ContractTerminal.TYPE, ContractScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(ContractStatePayload.TYPE, (payload, context) -> {
			if (Minecraft.getInstance().gui.screen() instanceof ContractScreen open) {
				open.show(payload.state());
			}
		});
	}
}
