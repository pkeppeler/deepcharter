package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "no-vanilla-advancements" for #835: a new player picks up an oak log, which used to raise a "Getting Wood"
 * style toast and a recipe toast, and the experience bar carries no locator dot. Then L opens the handbook, and so does the pause
 * menu's button, which reads "Handbook".
 */
public class NoVanillaAdvancementsScenario extends EvidenceScenario {
    private static final int FRAMES = 4;
    /** Long enough for a toast to slide in, which takes about a second. */
    private static final int TOAST_TICKS = 40;

    @Override
    protected String name() {
        return "no-vanilla-advancements";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context,
                    "the handbook in the inventory",
                    client -> handbookSlot(ClientSession.player(client).getInventory()) >= 0);
            singleplayer.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                ItemStack log = new ItemStack(Items.OAK_LOG);
                player.getInventory().add(log.copy());
                CriteriaTriggers.INVENTORY_CHANGED.trigger(player, player.getInventory(), log);
            });
            context.waitTicks(TOAST_TICKS); // tick-wait: a toast that does not come has nothing to await
            hold(context);
            screenshot(context, "oak-log-no-toast-no-dot");

            context.getInput().pressKey(options -> options.keyAdvancements);
            ClientWait.screen(context, HandbookScreen.class);
            hold(context);
            screenshot(context, "l-opens-handbook");
            context.setScreen(() -> null);

            context.setScreen(() -> new PauseScreen(true));
            ClientWait.screen(context, PauseScreen.class);
            hold(context);
            screenshot(context, "pause-menu-handbook-button");
            context.clickScreenButton("deepcharter.handbook.pause_button");
            ClientWait.screen(context, HandbookScreen.class);
            hold(context);
            context.setScreen(() -> null);
        }
    }

    private void hold(ClientGameTestContext context) {
        for (int frame = 0; frame < FRAMES; frame++) {
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
