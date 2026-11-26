package io.github.pkeppeler.deepcharter.test.support;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookTuning;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

/** Opens the inventory and finds and hovers the parts catalogue's cells, for the catalogue's client test and its evidence scenario. */
public final class CatalogueScene {
    /** A small chest: three rows of nine. */
    private static final int CHEST_SLOTS = 27;
    /** An item icon, and so a slot's hit area. */
    private static final int SLOT_ICON = 16;

    private CatalogueScene() {}

    /** Opens the player's inventory and waits until it is drawn. */
    public static void openInventory(ClientGameTestContext context) {
        context.setScreen(() -> new InventoryScreen(ClientSession.player(Minecraft.getInstance())));
        ClientWait.screen(context, InventoryScreen.class);
        context.waitTicks(3);
    }

    /**
     * Gives the player two iron and two copper, opens the inventory and waits until the server has taught the scanner unit's recipe from what
     * the player holds (ADR 0070). Without a known recipe the catalogue lists nothing. Returns the catalogue.
     */
    public static CataloguePanel teachScannerRecipe(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        ClientWait.until(context, "the player", client -> client.player != null && client.level != null);
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 2");
        singleplayer.getServer().runCommand("give @p minecraft:copper_ingot 2");
        ClientWait.until(
                context,
                "the iron and the copper in the inventory",
                client -> ClientSession.player(client).getInventory().contains(new ItemStack(Items.IRON_INGOT, 2))
                        && ClientSession.player(client).getInventory().contains(new ItemStack(Items.COPPER_INGOT, 2)));
        openInventory(context);
        CataloguePanel panel = panel(context);
        ClientWait.until(
                context,
                "the scanner unit's recipe, taught by what the player holds",
                client -> !panel.catalogue()
                        .recipesFor(ScannerRegistry.SCANNER_UNIT)
                        .isEmpty());
        return panel;
    }

    /**
     * Empties the player's inventory, gives two iron and two copper, lets the server teach what they teach (ADR 0070), then replaces the
     * client's recipe book with the {@link CatalogueFixtures} and {@link CatalogueFixtures#sundries()} and starts the catalogue afresh.
     * Opens the inventory and returns its catalogue, whose recipes and stock a test then knows exactly.
     */
    public static CataloguePanel knowFixturesHoldingIronAndCopper(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        ClientWait.until(context, "the player", client -> client.player != null && client.level != null);
        singleplayer.getServer().runCommand("clear @p");
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 2");
        singleplayer.getServer().runCommand("give @p minecraft:copper_ingot 2");
        ClientWait.until(
                context,
                "two iron and two copper",
                client -> ClientSession.player(client).getInventory().countItem(Items.IRON_INGOT) == 2
                        && ClientSession.player(client).getInventory().countItem(Items.COPPER_INGOT) == 2);
        openInventory(context);
        CataloguePanel taught = panel(context);
        ClientWait.until(
                context,
                "the recipes the iron and the copper teach",
                client -> !taught.catalogue().usesOf(Items.IRON_INGOT).isEmpty()
                        && !taught.catalogue().usesOf(Items.COPPER_INGOT).isEmpty());
        bookSettled(context);
        context.runOnClient(client -> {
            ClientRecipeBook book = ClientSession.player(client).getRecipeBook();
            CatalogueFixtures.fill(book);
            book.add(CatalogueFixtures.sundries());
            book.rebuildCollections();
            CatalogueScreens.resetState();
        });
        context.setScreen(() -> null);
        openInventory(context);
        CataloguePanel panel = panel(context);
        ClientWait.until(
                context,
                "the catalogue of the fixtures",
                client -> !panel.catalogue().recipesFor(Items.LADDER).isEmpty()
                        && panel.catalogue().recipesFor(Items.IRON_BLOCK).isEmpty());
        Set<RecipeDisplayId> fixtures = CatalogueFixtures.entries().stream()
                .map(RecipeDisplayEntry::id)
                .collect(Collectors.toCollection(HashSet::new));
        fixtures.add(CatalogueFixtures.sundries().id());
        Set<RecipeDisplayId> held = context.computeOnClient(CatalogueScene::bookIds);
        if (!held.equals(fixtures)) {
            throw new AssertionError(
                    "the recipe book holds exactly the fixtures and the sundries: a recipe the server taught late"
                            + " landed on it; expected " + fixtures + ", got " + held);
        }
        return panel;
    }

    /**
     * Waits until the client's recipe book has not changed for two of the server's discovery polls ({@code progressPollTicks}), so
     * whatever the items held teach has arrived and nothing more is coming.
     */
    private static void bookSettled(ClientGameTestContext context) {
        int settle = 2 * HandbookTuning.DEFAULT.progressPollTicks();
        AtomicReference<Set<RecipeDisplayId>> last =
                new AtomicReference<>(context.computeOnClient(CatalogueScene::bookIds));
        AtomicLong since = new AtomicLong(context.computeOnClient(CatalogueScene::gameTime));
        ClientWait.until(
                context,
                "the recipe book to stop changing for " + settle + " ticks",
                () -> {
                    Set<RecipeDisplayId> now = context.computeOnClient(CatalogueScene::bookIds);
                    long time = context.computeOnClient(CatalogueScene::gameTime);
                    if (!now.equals(last.get())) {
                        last.set(now);
                        since.set(time);
                    }
                    return time - since.get() >= settle;
                },
                () -> last.get().size() + " recipes, unchanged since tick " + since.get());
    }

    private static long gameTime(Minecraft client) {
        return Objects.requireNonNull(client.level, "a level").getGameTime();
    }

    /** The ids of every recipe the client's recipe book holds. */
    private static Set<RecipeDisplayId> bookIds(Minecraft client) {
        return ClientSession.player(client).getRecipeBook().getCollections().stream()
                .flatMap(collection -> collection.getRecipes().stream())
                .map(RecipeDisplayEntry::id)
                .collect(Collectors.toSet());
    }

    /** Hovers {@code item}'s cell in the catalogue's grid, on the page it is on, and presses {@code key}. */
    public static void pressOn(ClientGameTestContext context, CataloguePanel panel, Item item, int key) {
        CatalogueLayout layout = layout(context, panel);
        int perPage = layout.shelf().perPage();
        int index = indexOf(context, panel, item);
        showPage(context, panel, perPage, index / perPage);
        hover(context, gridCell(layout, index % perPage));
        RealInput.key(context, key, 0);
    }

    /** A spot on the recipe console's glass by its heading, over no row and no control. */
    public static Rect consoleHeading(CatalogueLayout layout) {
        return new Rect(layout.card().right() - 4, layout.card().stationY(), 2, 2);
    }

    /** The icon of the first row of the recipe console's total materials. */
    public static Rect firstMaterialsRow(CatalogueLayout layout) {
        int icon = CatalogueLook.current().slotSize() - 2;
        return new Rect(layout.card().left(), layout.card().gridY(), icon, icon);
    }

    /** Empties the search and the lookup. The caller waits a tick or two for the list to follow. */
    public static void clearSearch(ClientGameTestContext context, CataloguePanel panel) {
        context.runOnClient(client -> {
            panel.state().setQuery("");
            panel.state().clear();
        });
    }

    /** Turns the catalogue to {@code page}, counting from 0. */
    public static void showPage(ClientGameTestContext context, CataloguePanel panel, int perPage, int page) {
        context.runOnClient(client -> {
            int pages = CatalogueState.pages(panel.shownItems().size(), perPage);
            panel.state().turn(page - panel.state().page(pages), pages);
        });
    }

    /** Opens the inventory with the recipe console showing: R on the scanner unit's cell once its recipe is taught. Returns the catalogue. */
    public static CataloguePanel openRecipeConsole(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CataloguePanel panel = teachScannerRecipe(context, singleplayer);
        clearSearch(context, panel);
        context.waitTicks(2); // tick-wait: the catalogue lists what it now knows before the cell is looked up
        CatalogueLayout layout = layout(context, panel);
        int perPage = layout.shelf().perPage();
        int index = indexOf(context, panel, ScannerRegistry.SCANNER_UNIT);
        showPage(context, panel, perPage, index / perPage);
        hover(context, gridCell(layout, index % perPage));
        context.getInput().pressKey(InputConstants.KEY_R);
        ClientWait.until(context, "the recipe console", client -> panel.showsRecipePanel());
        return panel;
    }

    /** The catalogue of the screen the client shows. */
    public static CataloguePanel panel(ClientGameTestContext context) {
        return context.computeOnClient(CatalogueScene::panelNow);
    }

    /** The catalogue of the screen the client shows, for a caller already on the client thread. */
    public static CataloguePanel panelNow(Minecraft client) {
        return Objects.requireNonNull(
                CatalogueScreens.panelOf(Objects.requireNonNull(client.gui.screen(), "a screen is open")),
                "the open screen has a catalogue");
    }

    /** Where the panels are, which must be shown (a window wide enough). */
    public static CatalogueLayout layout(ClientGameTestContext context, CataloguePanel panel) {
        return Objects.requireNonNull(
                context.computeOnClient(client -> panel.layout()), "the panels are laid out at the window's size");
    }

    /** Where a recipe console on a screen of its own stands: centred on {@code screen}, as the screen lays it out. */
    public static CatalogueLayout.Console centredConsole(ClientGameTestContext context, Screen screen) {
        return Objects.requireNonNull(
                context.computeOnClient(client -> CatalogueLayout.centred(
                        screen.width,
                        screen.height,
                        CatalogueLook.current(),
                        CrtTuning.current(),
                        client.font.lineHeight)),
                "the console fits the window");
    }

    /** The cell of grid position {@code index} on the page shown. */
    public static Rect gridCell(CatalogueLayout layout, int index) {
        CatalogueLayout.Shelf shelf = layout.shelf();
        CatalogueLook look = CatalogueLook.current();
        return new Rect(
                shelf.left() + (index % shelf.columns()) * look.gridCell(),
                shelf.gridY() + (index / shelf.columns()) * look.gridCell(),
                look.slotSize(),
                look.slotSize());
    }

    /** The position of {@code item} in the catalogue's list on the first page, as it is drawn. */
    public static int indexOf(ClientGameTestContext context, CataloguePanel panel, Item item) {
        List<Item> shown = context.computeOnClient(client -> panel.shownItems());
        int index = shown.indexOf(item);
        if (index < 0) {
            throw new AssertionError("the catalogue lists " + shown + ", not " + item);
        }
        return index;
    }

    /**
     * Moves the cursor to a corner of the inventory image that holds no slot, so no item is hovered and no name or tooltip is drawn. A test
     * that reads the screen's text through {@code DrawnText} does this first, because a hovered item's name is recorded with the rest.
     */
    public static void parkCursor(ClientGameTestContext context, CatalogueLayout layout) {
        ClientCursor.moveTo(
                context, layout.inventory().x() + 2, layout.inventory().y() + 2);
    }

    /** Moves the cursor onto the centre of {@code cell}. */
    public static void hover(ClientGameTestContext context, Rect cell) {
        ClientCursor.moveTo(context, cell.x() + cell.width() / 2.0, cell.y() + cell.height() / 2.0);
    }

    /**
     * Opens a chest's menu on the server, a real one the server keeps open, holding {@code contents} from its first slot, and waits until
     * its screen is drawn. A chest has no catalogue beside it.
     */
    public static ContainerScreen openChest(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, List<ItemStack> contents) {
        context.setScreen(() -> null);
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            SimpleContainer chest = new SimpleContainer(CHEST_SLOTS);
            for (int i = 0; i < contents.size(); i++) {
                chest.setItem(i, contents.get(i).copy());
            }
            player.openMenu(new SimpleMenuProvider(
                    (id, inventory, who) -> ChestMenu.threeRows(id, inventory, chest), Component.literal("Chest")));
        });
        ClientWait.screen(context, ContainerScreen.class);
        context.waitTicks(3);
        return context.computeOnClient(client -> ClientSession.screen(client, ContainerScreen.class));
    }

    /** The 16 x 16 cell on the screen of the first slot of {@code screen} that {@code which} picks, for a hover. */
    public static Rect slotCell(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, Predicate<Slot> which) {
        return context.computeOnClient(client -> {
            int left = ContainerScreenFields.intField(screen, "leftPos");
            int top = ContainerScreenFields.intField(screen, "topPos");
            for (Slot slot : screen.getMenu().slots) {
                if (which.test(slot)) {
                    return new Rect(left + slot.x, top + slot.y, SLOT_ICON, SLOT_ICON);
                }
            }
            throw new AssertionError("no slot of " + screen.getClass().getSimpleName() + " is the one asked for");
        });
    }
}
