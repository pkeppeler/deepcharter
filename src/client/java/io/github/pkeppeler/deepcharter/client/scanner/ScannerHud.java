package io.github.pkeppeler.deepcharter.client.scanner;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice.Cell;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;

/**
 * Side-view minimap of the ridden pod's surroundings, top right. Pod facing runs left to right. Drawn only while the pod has a
 * working scanner; its reach and detail follow the scanner's tier ({@link ScanSlice}).
 *
 * <p>Draws only with fill() and text(), in the fixed colours of {@link ScannerTuning}: neither reads
 * world light, so the map is as readable in the dark of a deep layer as on the surface. It reads the
 * client's own chunk data and rescans every {@link ScannerTuning#rescanTicks()} ticks.
 */
public final class ScannerHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "scanner");
	private static final TagKey<Block> GOLD_ORES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores/gold"));
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	private static final int TITLE_HEIGHT = 10;
	private static final int FRAME = 1;
	private static final int WHITE = 0xFFFFFFFF;
	/** Half the width the altimeter ({@code BreachHud}, top centre) can take: "-12,345 ft." is 66 pixels at GUI scale 1. */
	private static final int ALTIMETER_HALF_WIDTH = 36;
	/** GUI pixels kept clear between the altimeter and the panel. */
	private static final int ALTIMETER_GAP = 4;
	/** A Mole is two blocks tall: the pod fills the feet cell and the one above. */
	private static final int POD_CELLS_UP = 1;

	private static ScanSlice slice;
	private static int ticksUntilScan;

	private ScannerHud() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(ScannerHud::tick);
		HudElementRegistry.addLast(ID, ScannerHud::extract);
	}

	/**
	 * GUI pixels per cell: the tuned size when the panel fits the GUI and keeps right of the altimeter, otherwise the largest
	 * that does, down to 1. Below that the panel clips or touches the altimeter; a tier 1 panel's 41 rows need a GUI about
	 * 60 pixels tall.
	 */
	public static int cellSize(int guiWidth, int guiHeight, ScanArea area) {
		int clearOfAltimeter = guiWidth / 2 + ALTIMETER_HALF_WIDTH + ALTIMETER_GAP;
		int fitWidth = (guiWidth - TUNING.margin() - 2 * FRAME - clearOfAltimeter) / area.columns();
		int fitHeight = (guiHeight - 2 * (TUNING.margin() + FRAME) - TITLE_HEIGHT) / area.rows();
		return Math.max(1, Math.min(TUNING.cellPixels(), Math.min(fitWidth, fitHeight)));
	}

	/** Left edge in GUI pixels of the cell {@code ahead} blocks along the facing. */
	public static int cellLeft(int guiWidth, int guiHeight, ScanArea area, int ahead) {
		int cell = cellSize(guiWidth, guiHeight, area);
		return guiWidth - TUNING.margin() - FRAME - area.columns() * cell + (ahead + area.halfWidth()) * cell;
	}

	/** Top edge in GUI pixels of the cell {@code up} blocks above the pod's feet. */
	public static int cellTop(int guiWidth, int guiHeight, ScanArea area, int up) {
		return TUNING.margin() + TITLE_HEIGHT + FRAME + (area.up() - up) * cellSize(guiWidth, guiHeight, area);
	}

	private static void tick(Minecraft client) {
		if (client.level == null || client.player == null || !(client.player.getVehicle() instanceof PodEntity pod)) {
			slice = null;
			ticksUntilScan = 0;
			return;
		}
		if (ticksUntilScan > 0) {
			ticksUntilScan--;
			return;
		}
		slice = ScanSlice.scan(client.level, pod).orElse(null);
		ticksUntilScan = TUNING.rescanTicks() - 1;
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		ScanSlice scanned = slice;
		if (scanned == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		int guiWidth = client.getWindow().getGuiScaledWidth();
		int guiHeight = client.getWindow().getGuiScaledHeight();
		ScanArea area = scanned.area();
		int cell = cellSize(guiWidth, guiHeight, area);
		int left = cellLeft(guiWidth, guiHeight, area, -area.halfWidth());
		int right = cellLeft(guiWidth, guiHeight, area, area.halfWidth()) + cell;
		int top = cellTop(guiWidth, guiHeight, area, area.up());
		int bottom = cellTop(guiWidth, guiHeight, area, -area.down()) + cell;

		graphics.fill(left - FRAME, TUNING.margin(), right + FRAME, bottom + FRAME, TUNING.frameColor());
		graphics.text(client.font, Component.translatable("deepcharter.scanner.title", scanned.tier()), left, TUNING.margin() + 1, WHITE);
		graphics.fill(left, top, right, bottom, TUNING.airColor());
		for (int up = area.up(); up >= -area.down(); up--) {
			for (int ahead = -area.halfWidth(); ahead <= area.halfWidth(); ahead++) {
				Cell found = scanned.cell(ahead, up);
				if (!(found instanceof Cell.Air)) {
					fillCell(graphics, guiWidth, guiHeight, area, ahead, up, colour(found));
				}
			}
		}
		for (int up = 0; up <= POD_CELLS_UP; up++) {
			fillCell(graphics, guiWidth, guiHeight, area, 0, up, TUNING.podColor());
		}
	}

	private static void fillCell(GuiGraphicsExtractor graphics, int guiWidth, int guiHeight, ScanArea area, int ahead, int up, int colour) {
		int cell = cellSize(guiWidth, guiHeight, area);
		int x = cellLeft(guiWidth, guiHeight, area, ahead);
		int y = cellTop(guiWidth, guiHeight, area, up);
		graphics.fill(x, y, x + cell, y + cell, colour);
	}

	/** Opaque ARGB for a cell; ore gets its own colour per ore, falling back to a generic one. */
	private static int colour(Cell cell) {
		return switch (cell) {
			case Cell.Air air -> TUNING.airColor();
			case Cell.Rock rock -> TUNING.rockColor();
			case Cell.Gas gas -> TUNING.gasColor();
			case Cell.Ore ore -> ore.block().defaultBlockState().is(GOLD_ORES) ? TUNING.goldOreColor() : TUNING.oreColor();
		};
	}
}
