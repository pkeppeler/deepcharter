package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Client GameTest for #835: in the real game, getting an oak log shows no advancement or recipe toast, the client holds no vanilla
 * advancement, the locator bar rule is off, and both the advancements key (L) and the pause menu's Advancements button open the
 * handbook, never vanilla's advancements screen.
 */
public class NoVanillaAdvancementsClientTest implements FabricClientGameTest {
    /** Ticks to let a toast reach the screen if one were coming: a toast is queued the tick its packet arrives. */
    private static final int TOAST_WAIT_TICKS = 40;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
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
            context.waitTicks(
                    TOAST_WAIT_TICKS); // tick-wait: an absence has nothing to await, so it is given time to fail
            context.runOnClient(client -> {
                require(
                        client.gui.toastManager().getToast(AdvancementToast.class, Toast.NO_TOKEN) == null,
                        "an advancement toast is showing");
                require(
                        client.gui.toastManager().getToast(RecipeToast.class, Toast.NO_TOKEN) == null,
                        "a recipe toast is showing");
                var vanilla = ClientSession.connection(client).getAdvancements().tree().nodes().stream()
                        .map(node -> node.holder().id())
                        .filter(id -> id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE))
                        .toList();
                require(vanilla.isEmpty(), "the client was sent vanilla advancements: " + vanilla);
                // The oak log is an ingredient, so the charter's known recipes (ADR 0070) put its recipes in the book,
                // quietly:
                // what the book may hold is KnownRecipes' to test, and the absent toast above is what vanilla's unlock
                // would show.
            });
            require(
                    !singleplayer
                            .getServer()
                            .computeOnServer(server -> server.getGameRules().get(GameRules.LOCATOR_BAR)),
                    "the locator bar rule is on");

            context.getInput().pressKey(options -> options.keyAdvancements);
            ClientWait.screen(context, HandbookScreen.class);
            context.runOnClient(client -> require(
                    !(client.gui.screen() instanceof AdvancementsScreen),
                    "the advancements key opened vanilla's advancements screen"));
            context.runOnClient(
                    client -> ClientSession.screen(client, HandbookScreen.class).onClose());
            ClientWait.until(context, "the game after the handbook closed", client -> client.gui.screen() == null);

            context.setScreen(() -> new PauseScreen(true));
            ClientWait.screen(context, PauseScreen.class);
            context.clickScreenButton("deepcharter.handbook.pause_button");
            ClientWait.screen(context, HandbookScreen.class);
            context.runOnClient(
                    client -> ClientSession.screen(client, HandbookScreen.class).onClose());
            ClientWait.screen(context, PauseScreen.class);
            context.setScreen(() -> null);
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
