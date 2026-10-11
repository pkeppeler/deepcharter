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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.market.AccountHud;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud.Kind;
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
 * The cockpit HUD (#247, {@link CockpitLook}): while the local player sits in a pod and the theme picks a style, the housings, gauges, warning lamps, scanner
 * window and readout plate of that style are the HUD (and round 1's style a frame over the whole screen), and the vanilla hotbar, hearts, held item name and the
 * dismount hint are not drawn. On foot, and with style 0, nothing here draws or hides anything.
 */
public final class CockpitHud {
	/** How many warning lamps a style has: low fuel, low hull, burning, and any other warning of the readout. */
	public static final int LAMPS = 4;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "cockpit");
	/** The vanilla elements a seated pilot does not get: the cluster of hotbar, health, armour, hunger, air, experience, the held item's name and the action bar (which holds the dismount hint). */
	public static final List<Identifier> HIDDEN_WHILE_SEATED = List.of(VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR,
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

	/** The heat gauge's reading, 0 to 1: the burning of the hull, eased. */
	public static double heat() {
		return heat;
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
		if (layout.frame()) {
			CrtDraw.sprite(graphics, layout.sprite("frame"), 0, 0, width, height);
		}
		decal(graphics, layout, "part_a", layout.partA(), width, height);
		decal(graphics, layout, "part_b", layout.partB(), width, height);
		decal(graphics, layout, "part_c", layout.partC(), width, height);
		if (layout.scanner().on()) {
			ScannerHud.drawInWindow(graphics, client, layout.scanner());
		}
		decal(graphics, layout, "cap", layout.cap(), width, height);
		if (layout.scanner().on()) {
			ScannerHud.drawTitleUnder(graphics, client, layout.scanner());
		}
		PodEntity pod = (PodEntity) client.player.getVehicle();
		CockpitReading reading = CockpitReading.of(client, pod, heat);
		boolean readout = showsReadout(client);
		List<Line> lines = readout ? PodStatusHud.readout(client, pod) : List.of();
		if (readout) {
			boolean fuelLow = PodFuel.isLow(pod.fuel());
			boolean hullLow = reading.hull() <= look.hullLowFraction();
			gauge(graphics, layout.fuel(), reading.fuel(), fuelLow ? look.lowColor() : look.fuelColor(), look);
			gauge(graphics, layout.hull(), reading.hull(), hullLow ? look.lowColor() : look.hullColor(), look);
			gauge(graphics, layout.depth(), reading.depth(), look.depthColor(), look);
			gauge(graphics, layout.heat(), reading.heat(), look.heatColor(), look);
			gauge(graphics, layout.cargo(), reading.cargo(), look.cargoColor(), look);
			lamps(graphics, layout.lamps(), lampsLit(fuelLow, hullLow, pod.hullBurning(), lines),
					new int[] {look.lowColor(), look.lowColor(), look.heatColor(), look.depthColor()}, look);
		}
		decal(graphics, layout, "header", layout.header(), width, height);
		if (readout) {
			plate(graphics, client, layout, look, rows(pod, reading, layout, look, lines));
		}
	}

	private static void decal(GuiGraphicsExtractor graphics, Layout layout, String slot, Decal decal, int width, int height) {
		if (decal.on()) {
			CrtDraw.sprite(graphics, layout.sprite(slot), decal.left(width), decal.top(height), decal.width(), decal.height());
		}
	}

	/** Draws one bar gauge at {@code value} (0 to 1): segments of {@code color} lit from its start, at least one when the value is above 0, over unlit ones. */
	private static void gauge(GuiGraphicsExtractor graphics, Gauge gauge, double value, int color, CockpitLook look) {
		if (!gauge.on()) {
			return;
		}
		int left = gauge.area().left(graphics.guiWidth());
		int top = gauge.area().top(graphics.guiHeight());
		int width = gauge.area().width();
		int height = gauge.area().height();
		int lit = (int) Math.ceil(value * gauge.segments() - 1e-9);
		switch (gauge.kind()) {
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

	/** Which of the {@link #LAMPS} are lit: low fuel, low hull, the hull burning, and any line of the readout that is a warning ({@link Kind#ALERT}). */
	public static boolean[] lampsLit(boolean fuelLow, boolean hullLow, boolean burning, List<Line> readout) {
		return new boolean[] {fuelLow, hullLow, burning, readout.stream().anyMatch(line -> line.kind() == Kind.ALERT)};
	}

	/** The warning lamps: {@link #LAMPS} squares in a row (a slot wider than it is tall) or a column, each lit in its colour or off. */
	private static void lamps(GuiGraphicsExtractor graphics, Decal slot, boolean[] lit, int[] colors, CockpitLook look) {
		if (!slot.on()) {
			return;
		}
		boolean row = slot.width() >= slot.height();
		int size = ((row ? slot.width() : slot.height()) - look.lampGap() * (LAMPS - 1)) / LAMPS;
		int left = slot.left(graphics.guiWidth());
		int top = slot.top(graphics.guiHeight());
		for (int k = 0; k < LAMPS; k++) {
			int offset = k * (size + look.lampGap());
			int x = row ? left + offset : left;
			int y = row ? top : top + offset;
			graphics.fill(x, y, row ? x + size : left + slot.width(), row ? top + slot.height() : y + size, lit[k] ? colors[k] : look.offColor());
		}
	}

	/** One row of the plate: parts drawn side by side, each in its colour, {@link CockpitLook#tallyGap} apart. */
	public record Row(List<Line> parts) {
		static Row of(Line line) {
			return new Row(List.of(line));
		}

		int width(Font font, int gap) {
			int width = gap * (parts.size() - 1);
			for (Line part : parts) {
				width += font.width(part.text());
			}
			return width;
		}
	}

	/**
	 * The rows of the plate. A style that lists every line (round 1's) draws each line of the readout and the account line. A compact style draws the tally on one row (hull,
	 * fuel, cargo and depth, each in its gauge's colour) and the account line, and every other line of the readout over them or under them, by where the plate is anchored:
	 * the tally stays where it is as lines come and go.
	 */
	public static List<Row> rows(PodEntity pod, CockpitReading reading, Layout layout, CockpitLook look, List<Line> readout) {
		Optional<Line> account = AccountHud.text().map(text -> new Line(text, HudLook.current().accountColor()));
		if (!layout.compact()) {
			List<Row> rows = new ArrayList<>(readout.stream().map(Row::of).toList());
			account.ifPresent(line -> rows.add(Row.of(line)));
			return rows;
		}
		boolean fuelLow = PodFuel.isLow(pod.fuel());
		boolean hullLow = reading.hull() <= look.hullLowFraction();
		List<Row> base = new ArrayList<>(List.of(new Row(List.of(
				new Line(Component.translatable("hud.deepcharter.pod.tally_hull", Math.round(pod.hull()), Math.round(pod.maxHull())), hullLow ? look.lowColor() : look.hullColor()),
				new Line(Component.translatable("hud.deepcharter.pod.tally_fuel", Math.round(pod.fuel())), fuelLow ? look.lowColor() : look.fuelColor()),
				new Line(Component.translatable("hud.deepcharter.pod.tally_cargo", pod.cargoUsed()), look.cargoColor()),
				new Line(Component.translatable("hud.deepcharter.pod.tally_depth", Math.round(pod.getY())), look.depthColor())))));
		account.ifPresent(line -> base.add(Row.of(line)));
		List<Row> extra = readout.stream().filter(line -> line.kind() != Kind.BASE).map(Row::of).toList();
		List<Row> rows = new ArrayList<>();
		boolean fromBottom = layout.readout().anchorY() == 2;
		rows.addAll(fromBottom ? extra : base);
		rows.addAll(fromBottom ? base : extra);
		return rows;
	}

	/** The readout, on a plate that is as big as its longest row and the number of rows. */
	private static void plate(GuiGraphicsExtractor graphics, Minecraft client, Layout layout, CockpitLook look, List<Row> rows) {
		Font font = client.font;
		int gap = HudLook.current().podStatusLineGap();
		int widest = 0;
		for (Row row : rows) {
			widest = Math.max(widest, row.width(font, look.tallyGap()));
		}
		int pad = look.platePad();
		int plateWidth = widest + 2 * pad;
		int plateHeight = rows.size() * (font.lineHeight + gap) - gap + 2 * pad;
		int left = layout.readout().left(graphics.guiWidth(), plateWidth);
		int top = layout.readout().top(graphics.guiHeight(), plateHeight);
		CrtDraw.sprite(graphics, layout.sprite("plate"), left, top, plateWidth, plateHeight);
		int y = top + pad;
		for (Row row : rows) {
			int x = left + pad;
			for (Line part : row.parts()) {
				graphics.text(font, part.text(), x, y, part.color());
				x += font.width(part.text()) + look.tallyGap();
			}
			y += font.lineHeight + gap;
		}
	}
}
