package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.RecipeConsoleScreen;
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
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.ScreenScroll;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for #1651: the spec sheet key, pressed through the real keyboard, opens the sheet of the item under
 * the cursor wherever an item can be hovered: a cell of the parts catalogue, a slot of the inventory beside it, a
 * chest's slot, the pod screen's slot, a row of the repair station's shop and an item on a recipe console. Over the
 * catalogue the sheet opens there; over any other screen it opens on a screen of its own, and Escape goes back to the
 * screen under it. Over an empty slot the key opens nothing; away from any slot it opens the latest sheet offered,
 * which says LATEST. A text field that has the keyboard keeps the key as a letter, and the key held down does not
 * close the sheet it opened.
 */
public class SpecSheetKeyClientTest implements FabricClientGameTest {
    /** What a sheet opened by the key's fallback says beside its kind. */
    private static final String LATEST = "LATEST";

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
            theCatalogueSearchKeepsTheKeyAsALetter(context, dynamite);
            overAChest(context, singleplayer, latest);
            anAnvilNameKeepsTheKeyAsALetter(context, singleplayer, dynamite);
            overARepairStationRow(context, dynamite);
            overARecipeConsole(context);
            overThePodScreen(context, singleplayer, latest, dynamite);
            context.setScreen(() -> null);
        }
    }

    /**
     * A catalogue cell, an inventory slot beside it, and nothing hovered: the sheet opens over the catalogue each time,
     * the last one marked LATEST. Holding the key down does not put the sheet away again.
     */
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
        pressTheKeyAndSeeTheSheetOverTheCatalogue(context, panel, drill, false, "a hovered catalogue cell");

        theHeldKeyKeepsTheSheet(context, () -> context.computeOnClient(client -> panel.sheetItem() == drill));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the held key's sheet put away", client -> panel.sheetItem() == null);

        AbstractContainerScreen<?> inventory =
                context.computeOnClient(client -> ClientSession.screen(client, AbstractContainerScreen.class));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, inventory, slot -> slot.getItem().is(dynamite)));
        pressTheKeyAndSeeTheSheetOverTheCatalogue(context, panel, dynamite, false, "a hovered inventory slot");

        Container pack = slotsOfThePlayer(context);
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(context, inventory, slot -> !slot.hasItem() && slot.container == pack));
        theKeyOpensNothing(context, () -> panel.sheetItem() == null, "an empty inventory slot");

        CatalogueScene.parkCursor(context, layout);
        pressTheKeyAndSeeTheSheetOverTheCatalogue(context, panel, latest, true, "no slot: the latest sheet offered");
    }

    private static Container slotsOfThePlayer(ClientGameTestContext context) {
        return context.computeOnClient(client -> ClientSession.player(client).getInventory());
    }

    private static void pressTheKeyAndSeeTheSheetOverTheCatalogue(
            ClientGameTestContext context, CataloguePanel panel, Item item, boolean latest, String what) {
        Screen before = context.computeOnClient(client -> client.gui.screen());
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.until(
                context,
                "the sheet of " + item + " over the catalogue, from " + what,
                client -> panel.sheetItem() == item,
                client -> "the catalogue shows the sheet of " + panel.sheetItem());
        require(context.computeOnClient(client -> client.gui.screen()) == before, "the key keeps the inventory open");
        require(
                context.computeOnClient(client -> saysLatest(Objects.requireNonNull(client.gui.screen()))) == latest,
                "the sheet from " + what + (latest ? " says " : " does not say ") + LATEST);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the sheet put away", client -> panel.sheetItem() == null);
    }

    /**
     * Holds the key down over the item whose sheet {@code open} says is shown, lets it repeat as a held key does, and
     * sees the sheet stay: only a new press puts it away.
     */
    private static void theHeldKeyKeepsTheSheet(ClientGameTestContext context, BooleanSupplier open) {
        int key = context.computeOnClient(client ->
                KeyMappingHelper.getBoundKeyOf(CatalogueKeys.SPEC_SHEET).getValue());
        context.getInput().holdKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.until(context, "the sheet opened by the held key", open, () -> "no such sheet is open");
        for (int i = 0; i < 3; i++) {
            context.runOnClient(client -> client.keyboardHandler.keyPress(
                    client.getWindow().handle(), InputConstants.REPEAT, new KeyEvent(key, 0, 0)));
            context.waitTicks(1);
        }
        require(open.getAsBoolean(), "the held key's repeats do not put the sheet away");
        context.getInput().releaseKey(CatalogueKeys.SPEC_SHEET);
        context.waitTicks(1);
        require(open.getAsBoolean(), "letting the key go keeps the sheet");
    }

    /** Presses the key and sees, a few ticks on, that it opened nothing: {@code nothing} still holds. */
    private static void theKeyOpensNothing(ClientGameTestContext context, BooleanSupplier nothing, String where) {
        Screen before = context.computeOnClient(client -> client.gui.screen());
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        // tick-wait: an absence has nothing to await, so the key is given a few ticks to open something
        context.waitTicks(5);
        require(
                context.computeOnClient(client -> client.gui.screen() == before && nothing.getAsBoolean()),
                "the key over " + where + " opens nothing");
    }

    /** With the catalogue's search focused and an item under the cursor, the key types a letter and opens no sheet. */
    private static void theCatalogueSearchKeepsTheKeyAsALetter(ClientGameTestContext context, Item dynamite) {
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, layout.shelf().search());
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ClientWait.until(context, "the search focused", client -> panel.searchFocused());
        AbstractContainerScreen<?> inventory =
                context.computeOnClient(client -> ClientSession.screen(client, AbstractContainerScreen.class));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, inventory, slot -> slot.getItem().is(dynamite)));
        String letter = keyLetter(context);
        RealInput.type(context, letter);
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> panel.state().query()).endsWith(letter),
                "the key typed into the search, which reads '"
                        + context.computeOnClient(client -> panel.state().query()) + "'");
        require(context.computeOnClient(client -> panel.sheetItem() == null), "the key in the search opened a sheet");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        CatalogueScene.clearSearch(context, panel);
    }

    /** The letter the spec sheet key types: its bound key's name, in lower case. */
    private static String keyLetter(ClientGameTestContext context) {
        return context.computeOnClient(client ->
                CatalogueKeys.SPEC_SHEET.getTranslatedKeyMessage().getString().toLowerCase(Locale.ROOT));
    }

    /** An anvil with an item in it has its name field focused: the key types into the name and opens no sheet. */
    private static void anAnvilNameKeepsTheKeyAsALetter(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, Item dynamite) {
        context.setScreen(() -> null);
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            // Beside the player on a block of stone: an anvil with air under it falls, and its menu closes.
            BlockPos at = player.blockPosition().east(2);
            player.level().setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
            player.level().setBlockAndUpdate(at, Blocks.ANVIL.defaultBlockState());
            player.openMenu(new SimpleMenuProvider(
                    (id, inventory, who) ->
                            new AnvilMenu(id, inventory, ContainerLevelAccess.create(player.level(), at)),
                    Component.literal("Anvil")));
            player.containerMenu.getSlot(0).set(new ItemStack(dynamite));
            player.containerMenu.broadcastChanges();
        });
        ClientWait.screen(context, AnvilScreen.class);
        AnvilScreen anvil = context.computeOnClient(client -> ClientSession.screen(client, AnvilScreen.class));
        ClientWait.until(
                context,
                "the anvil's name field focused, with the dynamite in the anvil",
                client -> anvil.getFocused() instanceof EditBox name && name.isActive());
        EditBox name = context.computeOnClient(client -> (EditBox) Objects.requireNonNull(anvil.getFocused()));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, anvil, slot -> slot.hasItem() && slot.getItem().is(dynamite)));
        String before = context.computeOnClient(client -> name.getValue());
        String letter = keyLetter(context);
        RealInput.type(context, letter);
        context.waitTicks(1);
        String after = context.computeOnClient(client -> name.getValue());
        require(
                after.equals(before + letter),
                "the key typed into the anvil's name: '" + before + "' became '" + after + "'");
        require(
                context.computeOnClient(client -> client.gui.screen() == anvil),
                "the key in the anvil's name opened a sheet");
        context.setScreen(() -> null);
    }

    /**
     * A chest has no catalogue: the sheet opens on a screen of its own, Escape goes back to the chest, still open, and
     * the sheet's RECIPES link shows the recipes on a console over the chest. Over an empty slot the key opens nothing,
     * and away from any slot the latest sheet, marked LATEST.
     */
    private static void overAChest(ClientGameTestContext context, TestSingleplayerContext singleplayer, Item latest) {
        // An item the fixtures teach recipes of, for the RECIPES link.
        Item drill = HangarParts.DRILL_HEAD;
        ContainerScreen chest = CatalogueScene.openChest(context, singleplayer, List.of(new ItemStack(drill)));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, chest, slot -> slot.hasItem() && slot.getItem().is(drill)));
        pressTheKeyAndSeeTheSheetOverTheScreen(context, chest, drill, false, "a hovered chest slot");
        require(
                context.computeOnClient(client -> ClientSession.player(client).containerMenu instanceof ChestMenu),
                "the chest's menu stays open under the sheet");

        Rect drillSlot = CatalogueScene.slotCell(
                context, chest, slot -> slot.hasItem() && slot.getItem().is(drill));
        CatalogueScene.hover(context, drillSlot);
        theHeldKeyKeepsTheSheet(
                context,
                () -> context.computeOnClient(
                        client -> client.gui.screen() instanceof SpecSheetScreen sheet && sheetShows(sheet, drill)));
        theRecipesLinkShowsTheRecipesOverTheChest(context, chest, drill);

        Container pack = slotsOfThePlayer(context);
        CatalogueScene.hover(
                context, CatalogueScene.slotCell(context, chest, slot -> !slot.hasItem() && slot.container != pack));
        theKeyOpensNothing(context, () -> true, "an empty chest slot");

        CatalogueScene.hover(context, new CatalogueLayout.Rect(1, 1, 2, 2));
        pressTheKeyAndSeeTheSheetOverTheScreen(context, chest, latest, true, "no slot of the chest");
        context.setScreen(() -> null);
    }

    /**
     * From the sheet open over {@code chest}: RECIPES shows {@code item}'s recipes on a console, and Escape goes back.
     */
    private static void theRecipesLinkShowsTheRecipesOverTheChest(
            ClientGameTestContext context, ContainerScreen chest, Item item) {
        SpecSheetScreen sheet = context.computeOnClient(client -> ClientSession.screen(client, SpecSheetScreen.class));
        require(context.computeOnClient(client -> sheet.recipesLinkActive()), "the RECIPES link is lit over a chest");
        CatalogueScene.hover(context, Objects.requireNonNull(context.computeOnClient(client -> sheet.recipesLink())));
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        ClientWait.screen(context, RecipeConsoleScreen.class);
        String name = context.computeOnClient(
                client -> new ItemStack(item).getHoverName().getString().toUpperCase(Locale.ROOT));
        ClientWait.until(
                context,
                "the console titled with " + name,
                client -> String.join(
                                " ",
                                DrawnText.atCursor(Objects.requireNonNull(client.gui.screen())).stream()
                                        .map(DrawnText.Drawn::text)
                                        .toList())
                        .contains(name));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(
                context,
                "the chest back after the recipes",
                client -> client.gui.screen() == chest,
                client -> "the screen is " + client.gui.screen());
        require(
                context.computeOnClient(client -> ClientSession.player(client).containerMenu instanceof ChestMenu),
                "the chest's menu stays open under the recipes");
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
        pressTheKeyAndSeeTheSheetOverTheScreen(context, screen, dynamite, false, "a hovered shop row");
        context.setScreen(() -> null);
    }

    /** An item on a recipe console's own screen: the key opens its sheet, and Escape goes back to the console. */
    private static void overARecipeConsole(ClientGameTestContext context) {
        context.setScreen(() -> new RecipeConsoleScreen(null, HangarParts.DRILL_HEAD, CatalogueState.Mode.RECIPES));
        ClientWait.screen(context, RecipeConsoleScreen.class);
        context.waitTicks(2);
        RecipeConsoleScreen console =
                context.computeOnClient(client -> ClientSession.screen(client, RecipeConsoleScreen.class));
        int[] spot = context.computeOnClient(client -> {
            for (int y = 0; y < console.height; y += 2) {
                for (int x = 0; x < console.width; x += 2) {
                    if (console.itemAt(x, y) != null) {
                        return new int[] {x, y};
                    }
                }
            }
            throw new AssertionError("the recipe console shows no item");
        });
        Item item = Objects.requireNonNull(context.computeOnClient(client -> console.itemAt(spot[0], spot[1])));
        CatalogueScene.hover(context, new CatalogueLayout.Rect(spot[0], spot[1], 1, 1));
        pressTheKeyAndSeeTheSheetOverTheScreen(context, console, item, false, "an item on a recipe console");
        context.setScreen(() -> null);
    }

    /**
     * A seated pilot's pod screen: the key over a slot opens the item's sheet and Escape goes back to the pod screen;
     * away from any slot it opens the latest sheet, marked LATEST.
     */
    private static void overThePodScreen(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, Item latest, Item dynamite) {
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
        pressTheKeyAndSeeTheSheetOverTheScreen(context, pod, dynamite, false, "a hovered pod screen slot");
        CatalogueScene.hover(context, new CatalogueLayout.Rect(1, 1, 2, 2));
        pressTheKeyAndSeeTheSheetOverTheScreen(context, pod, latest, true, "no slot of the pod screen");
    }

    /**
     * Presses the key over {@code under}, sees {@code item}'s sheet on a screen of its own, marked LATEST when
     * {@code latest}, and Escape brings {@code under} back.
     */
    private static void pressTheKeyAndSeeTheSheetOverTheScreen(
            ClientGameTestContext context, Screen under, Item item, boolean latest, String what) {
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.until(
                context,
                "the sheet of " + item + " on its own screen, from " + what,
                client -> client.gui.screen() instanceof SpecSheetScreen sheet && sheetShows(sheet, item),
                client -> "the screen is " + client.gui.screen());
        require(
                context.computeOnClient(client -> saysLatest(Objects.requireNonNull(client.gui.screen()))) == latest,
                "the sheet from " + what + (latest ? " says " : " does not say ") + LATEST);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(
                context,
                "the screen under the sheet back, from " + what,
                client -> client.gui.screen() == under,
                client -> "the screen is " + client.gui.screen());
    }

    private static boolean sheetShows(Screen screen, Item item) {
        String title = new ItemStack(item).getHoverName().getString().toUpperCase(Locale.ROOT);
        return DrawnText.atCursor(screen).stream().anyMatch(text -> text.text().equals(title));
    }

    private static boolean saysLatest(Screen screen) {
        return DrawnText.atCursor(screen).stream().anyMatch(text -> text.text().equals(LATEST));
    }
}
