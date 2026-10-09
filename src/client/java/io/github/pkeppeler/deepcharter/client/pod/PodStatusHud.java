package io.github.pkeppeler.deepcharter.client.pod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodSeat;

/** Plain text readout of the ridden pod; its margin, spacing and colour are the HUD theme's. */
public final class PodStatusHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_status");

	private PodStatusHud() {
	}

	public static void init() {
		HudElementRegistry.addLast(ID, PodStatusHud::extract);
	}

	/**
	 * Public so tests can check the text without reading pixels. The hull is shown as points out of the most the pod's parts allow:
	 * the client works the maximum out itself, from the synced component state ({@link io.github.pkeppeler.deepcharter.pod.PodComponents#STATE}).
	 */
	public static List<Component> lines(PodEntity pod) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("hud.deepcharter.pod.hull", Math.round(pod.hull()), Math.round(pod.maxHull())));
		lines.add(Component.translatable("hud.deepcharter.pod.fuel", Math.round(pod.fuel())));
		lines.add(Component.translatable("hud.deepcharter.pod.cargo", pod.cargoUsed()));
		lines.add(Component.translatable("hud.deepcharter.pod.depth", Math.round(pod.getY())));
		if (pod.stranded()) {
			lines.add(Component.translatable("hud.deepcharter.pod.stranded"));
		}
		return lines;
	}

	/** The warning line shown, in its own colour, while lava burns the hull. */
	public static Optional<Component> burningLine(PodEntity pod) {
		return pod.hullBurning() ? Optional.of(Component.translatable("hud.deepcharter.pod.burning")) : Optional.empty();
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !(client.player.getVehicle() instanceof PodEntity pod) || !PodSeat.showsPodStatus(pod, client.player)) {
			return;
		}
		Font font = client.font;
		HudLook look = HudLook.current();
		int y = look.podStatusMargin();
		for (Component line : lines(pod)) {
			graphics.text(font, line, look.podStatusMargin(), y, look.podStatusColor());
			y += font.lineHeight + look.podStatusLineGap();
		}
		int burningY = y;
		burningLine(pod).ifPresent(line -> graphics.text(font, line, look.podStatusMargin(), burningY, look.podBurningColor()));
	}
}
