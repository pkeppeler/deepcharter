package io.github.pkeppeler.deepcharter.client.scanner;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice.Cell;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;

/**
 * Side-view minimap of the ridden pod's surroundings, top right. Pod facing runs left to right.
 *
 * <p>Draws only with fill() and text(), in the fixed colours of {@link ScannerTuning}: neither reads
 * world light, so the map is as readable in the dark of a deep layer as on the surface. It reads the
 * client's own chunk data and rescans every {@link ScannerTuning#rescanTicks()} ticks.
 */
public final class ScannerHud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "scanner");
	private static final TagKey<Block> GOLD_ORES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores/gold"));
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	private static final Component TITLE = Component.translatable("deepcharter.scanner.title");
	private static final int TITLE_HEIGHT = 10;
	private static final int FRAME = 1;
	private static final int WHITE = 0xFFFFFFFF;
	private static ScanSlice slice;
	/** The cells above the feet cell that the ridden pod fills: 1 for a Mole, which is two blocks tall, 2 for a Prospector. */
	private static int podCellsUp;
	private static int ticksUntilScan;

	private ScannerHud() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(ScannerHud::tick);
		HudElementRegistry.addLast(ID, ScannerHud::extract);
	}

	/**
	 * GUI pixels per cell: the tuned size when the panel fits the GUI, otherwise the largest that does,
	 * down to 1. Below that the panel clips; the 41 rows need a GUI about 60 pixels tall.
	 */
	public static int cellSize(int guiWidth, int guiHeight) {
		int fitWidth = (guiWidth - 2 * (TUNING.margin() + FRAME)) / TUNING.columns();
		int fitHeight = (guiHeight - 2 * (TUNING.margin() + FRAME) - TITLE_HEIGHT) / TUNING.rows();
		return Math.max(1, Math.min(TUNING.cellPixels(), Math.min(fitWidth, fitHeight)));
	}

	/** Left edge in GUI pixels of the cell {@code ahead} blocks along the facing. */
	public static int cellLeft(int guiWidth, int guiHeight, int ahead) {
		int cell = cellSize(guiWidth, guiHeight);
		return guiWidth - TUNING.margin() - FRAME - TUNING.columns() * cell + (ahead + TUNING.halfWidth()) * cell;
	}

	/** Top edge in GUI pixels of the cell {@code up} blocks above the pod's feet. */
	public static int cellTop(int guiWidth, int guiHeight, int up) {
		return TUNING.margin() + TITLE_HEIGHT + FRAME + (TUNING.up() - up) * cellSize(guiWidth, guiHeight);
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
		slice = ScanSlice.scan(client.level, pod.blockPosition(), pod.getDirection());
		podCellsUp = Mth.ceil(pod.chassis().height()) - 1;
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
		int cell = cellSize(guiWidth, guiHeight);
		int left = cellLeft(guiWidth, guiHeight, -TUNING.halfWidth());
		int right = cellLeft(guiWidth, guiHeight, TUNING.halfWidth()) + cell;
		int top = cellTop(guiWidth, guiHeight, TUNING.up());
		int bottom = cellTop(guiWidth, guiHeight, -TUNING.down()) + cell;

		graphics.fill(left - FRAME, TUNING.margin(), right + FRAME, bottom + FRAME, TUNING.frameColor());
		graphics.text(client.font, TITLE, left, TUNING.margin() + 1, WHITE);
		graphics.fill(left, top, right, bottom, TUNING.airColor());
		for (int up = TUNING.up(); up >= -TUNING.down(); up--) {
			for (int ahead = -TUNING.halfWidth(); ahead <= TUNING.halfWidth(); ahead++) {
				Cell found = scanned.cell(ahead, up);
				if (!(found instanceof Cell.Air)) {
					fillCell(graphics, guiWidth, guiHeight, ahead, up, colour(found));
				}
			}
		}
		for (int up = 0; up <= podCellsUp; up++) {
			fillCell(graphics, guiWidth, guiHeight, 0, up, TUNING.podColor());
		}
	}

	private static void fillCell(GuiGraphicsExtractor graphics, int guiWidth, int guiHeight, int ahead, int up, int colour) {
		int cell = cellSize(guiWidth, guiHeight);
		int x = cellLeft(guiWidth, guiHeight, ahead);
		int y = cellTop(guiWidth, guiHeight, up);
		graphics.fill(x, y, x + cell, y + cell, colour);
	}

	/** Opaque ARGB for a cell; ore gets its own colour per ore, falling back to a generic one. */
	private static int colour(Cell cell) {
		return switch (cell) {
			case Cell.Air air -> TUNING.airColor();
			case Cell.Rock rock -> TUNING.rockColor();
			case Cell.Ore ore -> ore.block().defaultBlockState().is(GOLD_ORES) ? TUNING.goldOreColor() : TUNING.oreColor();
		};
	}
}
