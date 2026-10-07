package io.github.pkeppeler.deepcharter.client.pod;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;

/**
 * Text readout of the pod the player is riding: hull, fuel, cargo and depth, down the left edge.
 * It is plain text on purpose: the real HUD design comes later, and every value here is already
 * synced data, so it only changes how they are drawn.
 */
public final class PodStatusHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_status");
	private static final int MARGIN = 4;
	private static final int WHITE = 0xFFFFFFFF;

	private PodStatusHud() {
	}

	public static void init() {
		HudElementRegistry.addLast(ID, PodStatusHud::extract);
	}

	/** The lines the HUD shows for a pod. Public so that tests can check them without reading pixels. */
	public static List<Component> lines(PodEntity pod) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("hud.deepcharter.pod.hull", Math.round(pod.hull())));
		lines.add(Component.translatable("hud.deepcharter.pod.fuel", Math.round(pod.fuel())));
		lines.add(Component.translatable("hud.deepcharter.pod.cargo", pod.cargoUsed()));
		lines.add(Component.translatable("hud.deepcharter.pod.depth", Math.round(pod.getY())));
		if (pod.stranded()) {
			lines.add(Component.translatable("hud.deepcharter.pod.stranded"));
		}
		return lines;
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !(client.player.getVehicle() instanceof PodEntity pod)) {
			return;
		}
		Font font = client.font;
		int y = MARGIN;
		for (Component line : lines(pod)) {
			graphics.text(font, line, MARGIN, y, WHITE);
			y += font.lineHeight + 2;
		}
	}
}
