package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetOpener;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "m2-spec-sheet" for #848: the scanner unit's spec sheet opened from the recipe console's SPEC SHEET button (still
 * {@code sheet-open}), then the sheet that pops up by itself the first time the player crafts biofuel, taken from four wheat in the
 * inventory's grid (still {@code first-craft-popup}), then the longest sheet there is, a part's, looked up by hand (still
 * {@code sheet-tiers}). The craft is the server's real crafting path, so the pop-up is what a player
 * sees, not a fixture.
 */
public class SpecSheetScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    /** The advancement toasts of the first items held, which sit over the top of the screen, fade by now. */
    private static final int TOAST_TICKS = 60;
    /** The inventory's crafting grid is 2 x 2: slots 1 to 4, the result is slot 0. */
    private static final int GRID_SLOTS = 4;

    @Override
    protected String name() {
        return "m2-spec-sheet";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(context, "the player", client -> client.player != null && client.level != null);
            singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 2");
            singleplayer.getServer().runCommand("give @p minecraft:copper_ingot 2");
            ClientWait.until(
                    context,
                    "the iron and the copper in the inventory",
                    client -> ClientSession.player(client).getInventory().contains(new ItemStack(Items.IRON_INGOT, 2))
                            && ClientSession.player(client)
                                    .getInventory()
                                    .contains(new ItemStack(Items.COPPER_INGOT, 2)));
            CatalogueScene.openInventory(context);
            CataloguePanel panel = CatalogueScene.panel(context);
            ClientWait.until(
                    context,
                    "the scanner unit's recipe, taught by what the player holds",
                    client -> !panel.catalogue()
                            .recipesFor(ScannerRegistry.SCANNER_UNIT)
                            .isEmpty());
            context.waitTicks(TOAST_TICKS);
            ClientChecks.withHudHidden(context, () -> recordStills(context, singleplayer, panel));
            context.setScreen(() -> null);
        }
    }

    private void recordStills(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, CataloguePanel panel) {
        context.runOnClient(client ->
                panel.state().look(panel.catalogue(), ScannerRegistry.SCANNER_UNIT, CatalogueState.Mode.RECIPES));
        context.waitTicks(2);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, layout.card().spec());
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ClientWait.until(
                context, "the scanner unit's sheet", client -> panel.sheetItem() == ScannerRegistry.SCANNER_UNIT);
        CatalogueScene.parkCursor(context, layout);
        hold(context);
        screenshot(context, "sheet-open");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the sheet put away", client -> panel.sheetItem() == null);

        // Four wheat in the grid make biofuel, the first thing this player crafts that is Company issue.
        Item biofuel = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "biofuel"));
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            InventoryMenu menu = player.inventoryMenu;
            for (int slot = 1; slot <= GRID_SLOTS; slot++) {
                menu.getSlot(slot).set(new ItemStack(Items.WHEAT));
            }
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            menu.setCarried(ItemStack.EMPTY);
        });
        ClientWait.until(context, "the pop-up over the catalogue", client -> panel.sheetItem() == biofuel);
        hold(context);
        screenshot(context, "first-craft-popup");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the pop-up put away", client -> panel.sheetItem() == null);

        // The longest sheet there is, a part with a stock tier and six bought ones, the way a player looks one up
        // later.
        Item drillPart = ComponentItems.item(ComponentTrack.DRILL);
        context.runOnClient(client -> SpecSheetOpener.show(drillPart));
        ClientWait.until(context, "the drill part's sheet", client -> panel.sheetItem() == drillPart);
        hold(context);
        screenshot(context, "sheet-tiers");
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
