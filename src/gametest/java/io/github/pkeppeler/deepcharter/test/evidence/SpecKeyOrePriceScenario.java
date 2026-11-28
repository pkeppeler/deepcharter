package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetScreen;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Evidence scenario "spec-key-ore-price" for #1669 and #1651: an einsteinium's tooltip in the inventory, with its sale
 * price at the colony and at an outpost under its mass (still {@code ore-tooltip-price}); then a chest with dynamite in
 * it, the cursor on the dynamite (still {@code chest-slot-hovered}), and the spec sheet key pressed there, which opens
 * the dynamite's sheet over the chest (still {@code sheet-from-hovered-slot}).
 */
public class SpecKeyOrePriceScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    /** The advancement toasts of the first items held, which sit over the top of the screen, fade by now. */
    private static final int TOAST_TICKS = 60;

    @Override
    protected String name() {
        return "spec-key-ore-price";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(context, "the player", client -> client.player != null && client.level != null);
            Item einsteinium = OreRegistry.item(OreType.EINSTEINIUM);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.getInventory().add(OreRegistry.stack(OreType.EINSTEINIUM));
            });
            ClientWait.until(
                    context,
                    "the einsteinium in the inventory",
                    client -> ClientSession.player(client).getInventory().countItem(einsteinium) == 1);
            context.waitTicks(TOAST_TICKS);
            ClientChecks.withHudHidden(context, () -> recordStills(context, singleplayer, einsteinium));
            context.setScreen(() -> null);
        }
    }

    private void recordStills(ClientGameTestContext context, TestSingleplayerContext singleplayer, Item einsteinium) {
        CatalogueScene.openInventory(context);
        InventoryScreen inventory =
                context.computeOnClient(client -> ClientSession.screen(client, InventoryScreen.class));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, inventory, slot -> slot.getItem().is(einsteinium)));
        hold(context);
        screenshot(context, "ore-tooltip-price");

        Item dynamite = RepairRegistry.item(Consumable.DYNAMITE);
        ContainerScreen chest = CatalogueScene.openChest(context, singleplayer, List.of(new ItemStack(dynamite)));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, chest, slot -> slot.hasItem() && slot.getItem().is(dynamite)));
        hold(context);
        screenshot(context, "chest-slot-hovered");
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.screen(context, SpecSheetScreen.class);
        hold(context);
        screenshot(context, "sheet-from-hovered-slot");
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
