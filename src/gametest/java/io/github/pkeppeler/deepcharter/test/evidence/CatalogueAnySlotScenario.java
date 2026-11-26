package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m2-catalogue-any-slot" for #1266 (a): a chest, which has no catalogue beside it, holding iron and a diamond sword.
 * U over the iron opens its uses on a recipe console of its own ({@code chest-uses}), Escape comes back to the same chest
 * ({@code chest-back}), and R over the sword, whose recipe the charter does not know, opens nothing and says so by the cursor
 * ({@code chest-unknown}).
 */
public class CatalogueAnySlotScenario extends EvidenceScenario {
    /** Frames each state is held for, so a GIF reads at its pace. */
    private static final int REST_FRAMES = 12;
    /** The advancement toasts of the first items held fade by now. */
    private static final int TOAST_TICKS = 60;

    @Override
    protected String name() {
        return "m2-catalogue-any-slot";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            CatalogueScene.teachScannerRecipe(context, singleplayer);
            context.waitTicks(TOAST_TICKS);
            ClientChecks.withHudHidden(context, () -> record(context, singleplayer));
            context.setScreen(() -> null);
        }
    }

    private void record(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        ContainerScreen chest = CatalogueScene.openChest(
                context, singleplayer, List.of(new ItemStack(Items.IRON_INGOT, 8), new ItemStack(Items.DIAMOND_SWORD)));
        hold(context);
        CatalogueScene.hover(context, chestSlot(context, chest, 0));
        hold(context);
        RealInput.key(context, InputConstants.KEY_U, 0);
        ClientWait.until(
                context, "the recipe console", client -> client.gui.screen() != chest && client.gui.screen() != null);
        hold(context);
        screenshot(context, "chest-uses");

        RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
        ClientWait.until(context, "the chest back", client -> client.gui.screen() == chest);
        hold(context);
        screenshot(context, "chest-back");

        CatalogueScene.hover(context, chestSlot(context, chest, 1));
        hold(context);
        RealInput.key(context, InputConstants.KEY_R, 0);
        hold(context);
        screenshot(context, "chest-unknown");
    }

    private static Rect chestSlot(ClientGameTestContext context, ContainerScreen chest, int index) {
        return CatalogueScene.slotCell(
                context, chest, slot -> !(slot.container instanceof Inventory) && slot.getContainerSlot() == index);
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < REST_FRAMES; frame++) {
            frame(context);
        }
    }
}
