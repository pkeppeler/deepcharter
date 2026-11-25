package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetOpener;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetScreen;
import io.github.pkeppeler.deepcharter.colony.ColonyRations;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
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
 * Evidence scenario "m2-spec-sheet" for #848: the scanner unit's spec sheet opened from the recipe console's SPEC SHEET
 * button (still {@code sheet-open}), then the first time the player crafts biofuel, taken from three stalk mash in
 * the inventory's grid: no sheet opens, a corner slip names it and the key (still {@code first-craft-slip}), and the key
 * opens it (still {@code sheet-by-key}), then the longest sheet there is, a part's, looked up by hand (still
 * {@code sheet-tiers}). The craft is the server's real crafting path, so the slip is what a player sees, not a fixture.
 */
public class SpecSheetScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;
    /** The advancement toasts of the first items held, which sit over the top of the screen, fade by now. */
    private static final int TOAST_TICKS = 60;
    /** The inventory's crafting grid is 2 x 2: slots 1 to 4, the result is slot 0. Biofuel takes three stalk mash. */
    private static final int MASH_PER_BIOFUEL = 3;
    /** Long enough for the slip to slide in and be placed. */
    private static final int SLIP_SETTLE_TICKS = 10;

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

        // Three stalk mash in the grid make biofuel, the first thing this player crafts that is Company issue.
        Item biofuel = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "biofuel"));
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            InventoryMenu menu = player.inventoryMenu;
            for (int slot = 1; slot <= MASH_PER_BIOFUEL; slot++) {
                menu.getSlot(slot).set(new ItemStack(ColonyRations.STALK_MEAL));
            }
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            menu.setCarried(ItemStack.EMPTY);
        });
        // The scenario hides the HUD, and a hidden HUD does not run the toasts: the slip is awaited with it shown.
        ClientChecks.withHudShown(context, () -> {
            ClientWait.until(
                    context,
                    "the slip over the catalogue",
                    client -> DrawnText.of(graphics -> client.gui.toastManager().extractRenderState(graphics)).stream()
                            .anyMatch(text -> text.text()
                                    .contains(new ItemStack(biofuel)
                                            .getHoverName()
                                            .getString())));
            context.waitTicks(SLIP_SETTLE_TICKS);
            frame(context);
            screenshot(context, "first-craft-slip");
        });
        require(context.computeOnClient(client -> panel.sheetItem() == null), "the first craft opened no sheet");

        // The key opens the latest sheet, from the world, with no screen open.
        context.setScreen(() -> null);
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.screen(context, SpecSheetScreen.class);
        hold(context);
        screenshot(context, "sheet-by-key");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the sheet put away", client -> client.gui.screen() == null);
        CatalogueScene.openInventory(context);
        CataloguePanel reopened = CatalogueScene.panel(context);
        context.runOnClient(client -> client.gui.toastManager().clear());

        // The longest sheet there is, a part with a stock tier and six bought ones, the way a player looks one up
        // later.
        Item drillPart = ComponentItems.item(ComponentTrack.DRILL);
        context.runOnClient(client -> SpecSheetOpener.show(drillPart));
        ClientWait.until(context, "the drill part's sheet", client -> reopened.sheetItem() == drillPart);
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
