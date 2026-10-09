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
import io.github.pkeppeler.deepcharter.pod.PodLining;
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
		PodLining.State lining = PodLining.of(pod);
		if (lining.spoil() > 0 || lining.bricks() > 0) {
			lines.add(Component.translatable("hud.deepcharter.pod.slag", lining.bricks(), lining.spoil()));
		}
		if (pod.stranded()) {
			lines.add(Component.translatable("hud.deepcharter.pod.stranded"));
		}
		return lines;
	}

	/** The warning line shown, in its own colour, while lava burns the hull. */
	public static Optional<Component> burningLine(PodEntity pod) {
		return pod.hullBurning() ? Optional.of(Component.translatable("hud.deepcharter.pod.burning")) : Optional.empty();
	}

	/** The line shown, in its own colour, while the pilot lines the slab: the bricks placed so far. */
	public static Optional<Component> liningLine(PodEntity pod) {
		PodLining.State lining = PodLining.of(pod);
		return lining.working() ? Optional.of(Component.translatable("hud.deepcharter.pod.lining", lining.used())) : Optional.empty();
	}

	/** The warning line shown, in its own colour, after a lining stopped for want of slag brick. */
	public static Optional<Component> outOfBrickLine(PodEntity pod) {
		return PodLining.of(pod).dry() ? Optional.of(Component.translatable("hud.deepcharter.pod.lining_dry")) : Optional.empty();
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
		y = warning(graphics, font, look, burningLine(pod), look.podBurningColor(), y);
		y = warning(graphics, font, look, liningLine(pod), look.podLiningColor(), y);
		warning(graphics, font, look, outOfBrickLine(pod), look.podLiningDryColor(), y);
	}

	/** Draws {@code line} at {@code y} in {@code color} if there is one, and returns the y of the next line. */
	private static int warning(GuiGraphicsExtractor graphics, Font font, HudLook look, Optional<Component> line, int color, int y) {
		if (line.isEmpty()) {
			return y;
		}
		graphics.text(font, line.get(), look.podStatusMargin(), y, color);
		return y + font.lineHeight + look.podStatusLineGap();
	}
}
