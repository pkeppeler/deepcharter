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
	private static final int TITLE_HEIGHT = 10;
	private static final int FRAME = 1;
	private static final int WHITE = 0xFFFFFFFF;
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

	/** Left edge in GUI pixels of the cell {@code ahead} blocks along the facing. */
	public static int cellLeft(int guiWidth, int ahead) {
		return guiWidth - TUNING.margin() - FRAME - TUNING.columns() * TUNING.cellPixels()
				+ (ahead + TUNING.halfWidth()) * TUNING.cellPixels();
	}

	/** Top edge in GUI pixels of the cell {@code up} blocks above the pod's feet. */
	public static int cellTop(int up) {
		return TUNING.margin() + TITLE_HEIGHT + FRAME + (TUNING.up() - up) * TUNING.cellPixels();
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
		ticksUntilScan = TUNING.rescanTicks() - 1;
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		ScanSlice scanned = slice;
		if (scanned == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		int guiWidth = client.getWindow().getGuiScaledWidth();
		int cell = TUNING.cellPixels();
		int left = cellLeft(guiWidth, -TUNING.halfWidth());
		int right = cellLeft(guiWidth, TUNING.halfWidth()) + cell;
		int top = cellTop(TUNING.up());
		int bottom = cellTop(-TUNING.down()) + cell;

		graphics.fill(left - FRAME, TUNING.margin(), right + FRAME, bottom + FRAME, TUNING.frameColor());
		graphics.text(client.font, Component.translatable("deepcharter.scanner.title"), left, TUNING.margin() + 1, WHITE);
		graphics.fill(left, top, right, bottom, TUNING.airColor());
		for (int up = TUNING.up(); up >= -TUNING.down(); up--) {
			for (int ahead = -TUNING.halfWidth(); ahead <= TUNING.halfWidth(); ahead++) {
				Cell found = scanned.cell(ahead, up);
				if (!(found instanceof Cell.Air)) {
					fillCell(graphics, guiWidth, ahead, up, colour(found));
				}
			}
		}
		for (int up = 0; up <= POD_CELLS_UP; up++) {
			fillCell(graphics, guiWidth, 0, up, TUNING.podColor());
		}
	}

	private static void fillCell(GuiGraphicsExtractor graphics, int guiWidth, int ahead, int up, int colour) {
		int x = cellLeft(guiWidth, ahead);
		int y = cellTop(up);
		graphics.fill(x, y, x + TUNING.cellPixels(), y + TUNING.cellPixels(), colour);
	}

	/** Opaque ARGB for a cell; ore gets its own colour per ore, falling back to a generic one. */
	static int colour(Cell cell) {
		return switch (cell) {
			case Cell.Air air -> TUNING.airColor();
			case Cell.Rock rock -> TUNING.rockColor();
			case Cell.Ore ore -> ore.block().defaultBlockState().is(GOLD_ORES) ? TUNING.goldOreColor() : TUNING.oreColor();
		};
	}
}
