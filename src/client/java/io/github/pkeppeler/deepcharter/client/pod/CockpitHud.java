package io.github.pkeppeler.deepcharter.client.pod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.market.AccountHud;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud.Line;
import io.github.pkeppeler.deepcharter.client.scanner.ScannerHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.Gauge;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook.Layout;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFuel;
import io.github.pkeppeler.deepcharter.pod.PodSeat;

/**
 * The cockpit HUD (#247, {@link CockpitLook}): while the local player sits in a pod and the theme picks a style, the frame, housings, gauges, scanner
 * window and text readout of that style are the HUD, and the vanilla hotbar, hearts, held item name and the dismount hint are not drawn. On foot, and
 * with style 0, nothing here draws or hides anything.
 */
public final class CockpitHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cockpit");
	/** The vanilla elements a seated pilot does not get: the cluster of hotbar, health, armour, hunger, air, experience, the held item's name and the action bar (which holds the dismount hint). */
	private static final List<Identifier> HIDDEN_WHILE_SEATED = List.of(VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR,
			VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
			VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.OVERLAY_MESSAGE);

	private static double heat;

	private CockpitHud() {
	}

	public static void init() {
		for (Identifier element : HIDDEN_WHILE_SEATED) {
			HudElementRegistry.replaceElement(element, original -> (graphics, deltaTracker) -> {
				if (seated(Minecraft.getInstance()).isEmpty()) {
					original.extractRenderState(graphics, deltaTracker);
				}
			});
		}
		// The first element, so that what other mods and features draw (the altimeter, a transmission, the crosshair) is over the frame and never under it.
		HudElementRegistry.addFirst(ID, CockpitHud::extract);
		ClientTickEvents.END_CLIENT_TICK.register(CockpitHud::tick);
	}

	/** The layout in force when the local player sits in a pod and the theme picks a style; empty on foot or with no style. */
	public static Optional<Layout> seated(Minecraft client) {
		if (client.player == null || !(client.player.getVehicle() instanceof PodEntity)) {
			return Optional.empty();
		}
		return CockpitLook.current().active();
	}

	/** Whether the cockpit's plate shows the text readout: the local player is the pilot of a pod and the theme picks a style. The plain HUD's readout and account line stay quiet then. */
	public static boolean showsReadout(Minecraft client) {
		return seated(client).isPresent() && client.player.getVehicle() instanceof PodEntity pod && PodSeat.showsPodStatus(pod, client.player);
	}

	/** The vanilla elements the cockpit replaces, for a test to hold to the list. */
	public static List<Identifier> hiddenWhileSeated() {
		return HIDDEN_WHILE_SEATED;
	}

	private static void tick(Minecraft client) {
		if (client.player == null || !(client.player.getVehicle() instanceof PodEntity pod)) {
			heat = 0;
			return;
		}
		CockpitLook look = CockpitLook.current();
		heat = pod.hullBurning() ? Math.min(1.0, heat + look.heatRisePerTick()) : Math.max(0.0, heat - look.heatFallPerTick());
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		Optional<Layout> seated = seated(client);
		if (seated.isEmpty()) {
			return;
		}
		Layout layout = seated.get();
		CockpitLook look = CockpitLook.current();
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		CrtDraw.sprite(graphics, layout.sprite("frame"), 0, 0, width, height);
		decal(graphics, layout, "part_a", layout.partA(), width, height);
		decal(graphics, layout, "part_b", layout.partB(), width, height);
		if (layout.scanner().on()) {
			ScannerHud.drawInWindow(graphics, client, layout.scanner());
		}
		decal(graphics, layout, "cap", layout.cap(), width, height);
		if (layout.scanner().on()) {
			ScannerHud.drawTitleUnder(graphics, client, layout.scanner());
		}
		PodEntity pod = (PodEntity) client.player.getVehicle();
		if (showsReadout(client)) {
			CockpitReading reading = CockpitReading.of(client, pod, heat);
			boolean fuelLow = PodFuel.isLow(pod.fuel());
			boolean hullLow = reading.hull() <= look.hullLowFraction();
			gauge(graphics, layout.fuel(), reading.fuel(), fuelLow ? look.lowColor() : look.fuelColor(), fuelLow, look);
			gauge(graphics, layout.hull(), reading.hull(), hullLow ? look.lowColor() : look.hullColor(), hullLow, look);
			gauge(graphics, layout.depth(), reading.depth(), look.depthColor(), false, look);
			gauge(graphics, layout.heat(), reading.heat(), look.heatColor(), reading.heat() > look.heatWarning(), look);
		}
		decal(graphics, layout, "header", layout.header(), width, height);
		if (showsReadout(client)) {
			List<Line> readout = new ArrayList<>(PodStatusHud.readout(client, pod));
			AccountHud.text().ifPresent(account -> readout.add(new Line(account, HudLook.current().accountColor())));
			plate(graphics, client, layout, look, readout);
		}
	}

	private static void decal(GuiGraphicsExtractor graphics, Layout layout, String slot, Decal decal, int width, int height) {
		if (decal.on()) {
			CrtDraw.sprite(graphics, layout.sprite(slot), decal.left(width), decal.top(height), decal.width(), decal.height());
		}
	}

	/**
	 * Draws one gauge at {@code value} (0 to 1). A dial turns its needle, which is {@code color} in a warning and the needle colour otherwise; a bar lights
	 * segments of {@code color} from its start, at least one when the value is above 0, over unlit ones.
	 */
	private static void gauge(GuiGraphicsExtractor graphics, Gauge gauge, double value, int color, boolean warning, CockpitLook look) {
		if (!gauge.on()) {
			return;
		}
		int left = gauge.area().left(graphics.guiWidth());
		int top = gauge.area().top(graphics.guiHeight());
		int width = gauge.area().width();
		int height = gauge.area().height();
		int lit = (int) Math.ceil(value * gauge.segments() - 1e-9);
		switch (gauge.kind()) {
			case DIAL -> needle(graphics, left + width / 2, top + height / 2, Math.min(width, height) / 2 - look.needleInset(),
					Math.toRadians(gauge.from() + (gauge.to() - gauge.from()) * value), warning ? look.lowColor() : look.needleColor(), look.hubSize());
			case ACROSS -> {
				for (int i = 0; i < gauge.segments(); i++) {
					int from = left + i * width / gauge.segments();
					int to = left + (i + 1) * width / gauge.segments() - 1;
					graphics.fill(from, top, to, top + height, i < lit ? color : look.offColor());
				}
			}
			case UP -> {
				for (int i = 0; i < gauge.segments(); i++) {
					int bottom = top + height - i * height / gauge.segments();
					int from = top + height - (i + 1) * height / gauge.segments() + 1;
					graphics.fill(left, from, left + width, bottom, i < lit ? color : look.offColor());
				}
			}
		}
	}

	/** A needle one pixel thick from ({@code cx}, {@code cy}) {@code length} pixels out at {@code angle} radians clockwise from straight up, and a square hub of {@code hub} pixels. */
	private static void needle(GuiGraphicsExtractor graphics, int cx, int cy, int length, double angle, int color, int hub) {
		for (int step = 0; step <= length; step++) {
			int x = cx + (int) Math.round(Math.sin(angle) * step);
			int y = cy - (int) Math.round(Math.cos(angle) * step);
			graphics.fill(x, y, x + 1, y + 1, color);
		}
		graphics.fill(cx - hub / 2, cy - hub / 2, cx - hub / 2 + hub, cy - hub / 2 + hub, color);
	}

	/** The text readout, on a plate that is as big as its longest line and the number of lines. */
	private static void plate(GuiGraphicsExtractor graphics, Minecraft client, Layout layout, CockpitLook look, List<Line> lines) {
		Font font = client.font;
		int gap = HudLook.current().podStatusLineGap();
		int widest = 0;
		for (Line line : lines) {
			widest = Math.max(widest, font.width(line.text()));
		}
		int pad = look.platePad();
		int plateWidth = widest + 2 * pad;
		int plateHeight = lines.size() * (font.lineHeight + gap) - gap + 2 * pad;
		int left = layout.readout().left(graphics.guiWidth(), plateWidth);
		int top = layout.readout().top(graphics.guiHeight(), plateHeight);
		CrtDraw.sprite(graphics, layout.sprite("plate"), left, top, plateWidth, plateHeight);
		int y = top + pad;
		for (Line line : lines) {
			graphics.text(font, line.text(), left + pad, y, line.color());
			y += font.lineHeight + gap;
		}
	}
}
