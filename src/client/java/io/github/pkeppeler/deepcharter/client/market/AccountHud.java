package io.github.pkeppeler.deepcharter.client.market;

import java.util.Optional;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.charter.ClientCharter;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;

/** The charter's name and balance, at the left edge halfway down so it clears the pod readout and the altimeter. Shown while on a charter. */
public final class AccountHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "account");
	private static final int MARGIN = 4;

	private AccountHud() {
	}

	public static void init() {
		HudElementRegistry.addLast(ID, AccountHud::extract);
	}

	/** The text shown, or empty when the player is on no charter. Public so tests can check it without reading pixels. */
	public static Optional<Component> text() {
		return ClientCharter.view().map(charter -> Component.translatable("hud.deepcharter.account", charter.name(), charter.balance()));
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Optional<Component> text = text();
		if (text.isEmpty()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		graphics.text(font, text.get(), MARGIN, graphics.guiHeight() / 2, CrtTuning.DEFAULT.phosphorColor());
	}
}
