package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetScreen;
import io.github.pkeppeler.deepcharter.client.pod.PodScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.handbook.SpecSheetPopups;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Client GameTest for #1651: the spec sheet key, pressed through the real keyboard, opens the sheet of the item under the cursor
 * wherever an item can be hovered: a cell of the parts catalogue, a slot of the inventory beside it, a chest's slot, the pod screen's
 * slot and a row of the repair station's shop. Over the catalogue the sheet opens there; over any other screen it opens on its own
 * screen, and Escape goes back to the screen under it. With nothing hovered the key opens the latest sheet offered, as before.
 */
public class SpecSheetKeyClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player's inventory", client -> client.player != null && client.level != null);
            context.runOnClient(client ->
                    CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook()));
            Item latest = ComponentItems.item(ComponentTrack.FUEL_TANK);
            Item dynamite = RepairRegistry.item(Consumable.DYNAMITE);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                require(SpecSheetPopups.offer(player, latest), "the server offers the fuel tank's sheet");
                player.getInventory().add(new ItemStack(dynamite));
            });
            ClientWait.until(
                    context,
                    "the dynamite in the inventory",
                    client -> ClientSession.player(client).getInventory().countItem(dynamite) == 1);
            context.runOnClient(client -> client.gui.toastManager().clear());

            overTheCatalogue(context, latest, dynamite);
            overAChest(context, singleplayer);
            overARepairStationRow(context, dynamite);
            overThePodScreen(context, singleplayer, dynamite);
            context.setScreen(() -> null);
        }
    }

    /** A catalogue cell, an inventory slot beside it, and nothing hovered: the sheet opens over the catalogue each time. */
    private static void overTheCatalogue(ClientGameTestContext context, Item latest, Item dynamite) {
        CatalogueScene.openInventory(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueScene.clearSearch(context, panel);
        Item drill = HangarParts.DRILL_HEAD;
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        int perPage = layout.shelf().perPage();
        int index = CatalogueScene.indexOf(context, panel, drill);
        CatalogueScene.showPage(context, panel, perPage, index / perPage);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, index % perPage));
        pressTheKeyAndSeeTheSheetOverTheCatalogue(context, panel, drill, "a hovered catalogue cell");

        AbstractContainerScreen<?> inventory =
                context.computeOnClient(client -> ClientSession.screen(client, AbstractContainerScreen.class));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, inventory, slot -> slot.getItem().is(dynamite)));
        pressTheKeyAndSeeTheSheetOverTheCatalogue(context, panel, dynamite, "a hovered inventory slot");

        CatalogueScene.parkCursor(context, layout);
        pressTheKeyAndSeeTheSheetOverTheCatalogue(context, panel, latest, "nothing hovered: the latest sheet offered");
    }

    private static void pressTheKeyAndSeeTheSheetOverTheCatalogue(
            ClientGameTestContext context, CataloguePanel panel, Item item, String what) {
        Screen before = context.computeOnClient(client -> client.gui.screen());
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.until(
                context,
                "the sheet of " + item + " over the catalogue, from " + what,
                client -> panel.sheetItem() == item,
                client -> "the catalogue shows the sheet of " + panel.sheetItem());
        require(context.computeOnClient(client -> client.gui.screen()) == before, "the key keeps the inventory open");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the sheet put away", client -> panel.sheetItem() == null);
    }

    /** A chest has no catalogue: the sheet opens on its own screen, and Escape goes back to the chest, still open. */
    private static void overAChest(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        Item cargo = ComponentItems.item(ComponentTrack.CARGO_BAY);
        ContainerScreen chest = CatalogueScene.openChest(context, singleplayer, List.of(new ItemStack(cargo)));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, chest, slot -> slot.hasItem() && slot.getItem().is(cargo)));
        pressTheKeyAndSeeTheSheetOverTheScreen(context, chest, cargo, "a hovered chest slot");
        require(
                context.computeOnClient(client -> ClientSession.player(client).containerMenu instanceof ChestMenu),
                "the chest's menu stays open under the sheet");
        context.setScreen(() -> null);
    }

    /** A row of the repair station's shop names an item: the key over it opens that item's sheet. */
    private static void overARepairStationRow(ClientGameTestContext context, Item dynamite) {
        BlockPos at =
                context.computeOnClient(client -> ClientSession.player(client).blockPosition());
        TerminalView view = new TerminalView(at, TerminalTypes.REPAIR_STATION.id(), Optional.empty(), List.of());
        context.setScreen(() -> new RepairStationScreen(view));
        ClientWait.screen(context, RepairStationScreen.class);
        RepairStationScreen screen =
                context.computeOnClient(client -> ClientSession.screen(client, RepairStationScreen.class));
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
        pressTheKeyAndSeeTheSheetOverTheScreen(context, screen, dynamite, "a hovered shop row");
        context.setScreen(() -> null);
    }

    /** A seated pilot's pod screen: the key over a slot opens the item's sheet, and Escape goes back to the pod screen. */
    private static void overThePodScreen(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, Item dynamite) {
        context.setScreen(() -> null);
        int[] at = singleplayer.getServer().computeOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            return new int[] {player.getBlockX(), player.getBlockZ()};
        });
        ClientWait.chunksAround(context, at[0], at[1], 4);
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
            pod.setPos(player.position());
            player.level().addFreshEntity(pod);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the player could not mount the new pod");
            }
        });
        ClientWait.until(
                context,
                "the pilot seated in the pod",
                client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
        context.getInput().pressKey(options -> options.keyInventory);
        ClientWait.screen(context, PodScreen.class);
        context.waitTicks(3);
        PodScreen pod = context.computeOnClient(client -> ClientSession.screen(client, PodScreen.class));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, pod, slot -> slot.hasItem() && slot.getItem().is(dynamite)));
        pressTheKeyAndSeeTheSheetOverTheScreen(context, pod, dynamite, "a hovered pod screen slot");
    }

    /** Presses the key over {@code under}, sees {@code item}'s sheet on its own screen, and Escape brings {@code under} back. */
    private static void pressTheKeyAndSeeTheSheetOverTheScreen(
            ClientGameTestContext context, Screen under, Item item, String what) {
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.until(
                context,
                "the sheet of " + item + " on its own screen, from " + what,
                client -> client.gui.screen() instanceof SpecSheetScreen sheet && sheetShows(sheet, item),
                client -> "the screen is " + client.gui.screen());
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(
                context,
                "the screen under the sheet back, from " + what,
                client -> client.gui.screen() == under,
                client -> "the screen is " + client.gui.screen());
    }

    private static boolean sheetShows(Screen screen, Item item) {
        String title = new ItemStack(item).getHoverName().getString().toUpperCase(Locale.ROOT);
        return DrawnText.of(screen).stream().anyMatch(text -> text.text().equals(title));
    }
}
