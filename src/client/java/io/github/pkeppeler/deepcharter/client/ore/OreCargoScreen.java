package io.github.pkeppeler.deepcharter.client.ore;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;

/**
 * A plain panel showing a pod's cargo: its ore in the bay's slots, as many as the pod's bay has, and the mass they weigh.
 * A later issue restyles it.
 */
public class OreCargoScreen extends AbstractContainerScreen<OreCargoMenu> {
	/** Pixels under the last row of slots for the mass line. */
	private static final int FOOTER = 24;
	private static final int PANEL = 0xFFC6C6C6;
	private static final int EDGE = 0xFF373737;
	private static final int SLOT = 0xFF8B8B8B;
	private static final int TEXT = 0xFF404040;

	public OreCargoScreen(OreCargoMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, width(menu), height(menu));
	}

	private static int width(OreCargoMenu menu) {
		return 2 * OreCargoMenu.SLOT_X + OreCargoMenu.columns(menu.cargoSlots()) * OreCargoMenu.SLOT_SIZE - 2;
	}

	private static int height(OreCargoMenu menu) {
		return OreCargoMenu.SLOT_Y + OreCargoMenu.rows(menu.cargoSlots()) * OreCargoMenu.SLOT_SIZE + FOOTER;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		graphics.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, EDGE);
		graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);
		int columns = OreCargoMenu.columns(menu.cargoSlots());
		for (int i = 0; i < menu.cargoSlots(); i++) {
			int x = leftPos + OreCargoMenu.SLOT_X + i % columns * OreCargoMenu.SLOT_SIZE - 1;
			int y = topPos + OreCargoMenu.SLOT_Y + i / columns * OreCargoMenu.SLOT_SIZE - 1;
			graphics.fill(x, y, x + OreCargoMenu.SLOT_SIZE, y + OreCargoMenu.SLOT_SIZE, EDGE);
			graphics.fill(x + 1, y + 1, x + OreCargoMenu.SLOT_SIZE - 1, y + OreCargoMenu.SLOT_SIZE - 1, SLOT);
		}
		graphics.text(font, title, leftPos + OreCargoMenu.SLOT_X, topPos + 6, TEXT, false);
		graphics.text(font, Component.translatable("screen.deepcharter.cargo.mass", menu.cargoMass()),
				leftPos + OreCargoMenu.SLOT_X, topPos + OreCargoMenu.SLOT_Y + OreCargoMenu.rows(menu.cargoSlots()) * OreCargoMenu.SLOT_SIZE + 6, TEXT, false);
	}

	/** The panel draws its own title and mass, so nothing is left for the default labels. */
	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
	}
}
