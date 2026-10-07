package io.github.pkeppeler.deepcharter.client.upgrade;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;

import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeViewPayload;

/** Registers the upgrade terminal's online screen and the receiver that fills it with the parked pod. */
public final class UpgradeClientRegistry {
	private UpgradeClientRegistry() {
	}

	public static void register() {
		TerminalScreens.register(TerminalTypes.UPGRADE_TERMINAL, UpgradeScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(UpgradeViewPayload.TYPE, (payload, context) -> {
			if (Minecraft.getInstance().gui.screen() instanceof UpgradeScreen open) {
				open.show(payload.view());
			}
		});
	}
}
