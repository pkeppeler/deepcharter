package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.handbook.SpecSheetPopups;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Evidence scenario "spec-key-ore-price" for #1669 and #1651: an einsteinium's tooltip in the inventory, with its sale
 * price at the colony and at an outpost under its mass (still {@code ore-tooltip-price}); a chest with dynamite in it,
 * the cursor on the dynamite (still {@code chest-slot-hovered}), and the spec sheet key pressed there, which opens the
 * dynamite's sheet over the chest (still {@code sheet-from-hovered-slot}); Escape back to the chest (still
 * {@code escape-back-to-chest}); the key away from any slot, which opens the latest sheet offered, marked LATEST (still
 * {@code latest-away-from-any-slot}); and the repair station's shop with the cursor on the dynamite's row (still
 * {@code shop-row-hovered}) and the key pressed there (still {@code sheet-from-shop-row}).
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
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the chest back under the sheet", client -> client.gui.screen() == chest);
        hold(context);
        screenshot(context, "escape-back-to-chest");

        // Away from any slot, the key opens the latest sheet offered, which says LATEST.
        singleplayer
                .getServer()
                .runOnServer(server -> SpecSheetPopups.offer(
                        server.getPlayerList().getPlayers().getFirst(), ComponentItems.item(ComponentTrack.FUEL_TANK)));
        context.waitTicks(2);
        CatalogueScene.hover(context, new CatalogueLayout.Rect(1, 1, 2, 2));
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.screen(context, SpecSheetScreen.class);
        hold(context);
        screenshot(context, "latest-away-from-any-slot");
        context.setScreen(() -> null);

        shopRow(context, dynamite);
    }

    /** The repair station's shop, the cursor on the dynamite's row, and the key pressed there. */
    private void shopRow(ClientGameTestContext context, Item dynamite) {
        BlockPos at =
                context.computeOnClient(client -> ClientSession.player(client).blockPosition());
        TerminalView view = new TerminalView(at, TerminalTypes.REPAIR_STATION.id(), Optional.empty(), List.of());
        context.setScreen(() -> new RepairStationScreen(view));
        ClientWait.screen(context, RepairStationScreen.class);
        RepairStationScreen screen =
                context.computeOnClient(client -> ClientSession.screen(client, RepairStationScreen.class));
        ClientWait.until(
                context, "the intro typed", client -> screen.typewriter().done());
        String name = context.computeOnClient(
                client -> new ItemStack(dynamite).getHoverName().getString().toUpperCase(Locale.ROOT));
        CrtButton row = context.computeOnClient(client -> {
            for (int first = 0; first < screen.rowCount(); first++) {
                ScreenScroll.scrollTo(screen, first);
                for (GuiEventListener child : screen.children()) {
                    if (child instanceof CrtButton button
                            && button.getMessage().getString().contains(name)) {
                        return button;
                    }
                }
            }
            throw new AssertionError("no row of the repair station names " + name);
        });
        CatalogueScene.hover(
                context, new CatalogueLayout.Rect(row.getX(), row.getY(), row.getWidth(), row.getHeight()));
        hold(context);
        screenshot(context, "shop-row-hovered");
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.screen(context, SpecSheetScreen.class);
        hold(context);
        screenshot(context, "sheet-from-shop-row");
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int frame = 0; frame < HOLD_FRAMES; frame++) {
            frame(context);
        }
    }
}
