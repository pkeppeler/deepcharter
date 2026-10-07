package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.world.entity.player.Inventory;

import io.github.pkeppeler.deepcharter.handbook.HandbookItems;

/** Evidence scenario "m2-handbook-core" for #61: a new player holds the Employee Handbook in the hotbar. */
public class HandbookCoreScenario extends EvidenceScenario {
	@Override
	protected String name() {
		return "m2-handbook-core";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> handbookSlot(client.player.getInventory()) >= 0);
			context.runOnClient(client -> client.player.getInventory().setSelectedSlot(handbookSlot(client.player.getInventory())));
			context.waitTicks(2);
			screenshot(context, "handbook-in-hotbar");
			frame(context);
		}
	}

	private static int handbookSlot(Inventory inventory) {
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			if (HandbookItems.isHandbook(inventory.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}
}
