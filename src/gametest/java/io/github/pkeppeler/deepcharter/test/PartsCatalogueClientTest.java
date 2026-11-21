package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.Bench;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueFavourites;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueSearch;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.KnownRecipe;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.PartsCatalogue;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetHook;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * Client GameTest for #846, the parts catalogue. The model (what is known, the search, craftable now, the lookup, the favourites file) and
 * the layout at each window size are checked with no game running. Then the real game: the panels beside the inventory, the hidden
 * recipe-book button, R and U on a hovered item, the search, the favourites, the craftable filter, and that every string the panels draw
 * lies whole inside its panel's glass (a string past the glass is clipped away, and {@code DrawnText} drops a string wholly outside its
 * scissor, so a missing string fails too).
 */
public class PartsCatalogueClientTest implements FabricClientGameTest {
    private static final Item DRILL = HangarParts.DRILL_HEAD;
    private static final int LEFT_MOUSE = 0;
    private static final int RIGHT_MOUSE = 1;
    /** The default window, 854 by 480 at GUI scale 2. */
    private static final int WIDTH = 427;

    private static final int HEIGHT = 240;
    /** The inventory's crafting grid is 2 x 2, the crafting table's 3 x 3. */
    private static final Bench TABLE = new Bench(RecipeBookType.CRAFTING, 3, 3);

    private static final Bench POCKET = new Bench(RecipeBookType.CRAFTING, 2, 2);
    /** The narrowest GUI the panels draw at, and one pixel under it. */
    private static final int NARROWEST = 425;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        context.runOnClient(client -> {
            onlyTheKnownAreListed();
            theSearchMatchesNamesIdsAndNamespaces();
            craftableNowNeedsTheIngredientsInHand();
            craftableNowMeansCraftableOnThisScreen();
            theLookupListsRecipesAndUsesAndStepsThrough();
            pagesStopAtTheEnds();
            favouritesAreKeptPerPlayerAndSurviveReopening();
            aFavouritesFileOfAnotherVersionIsNeverWritten();
            thePanelsFitAtEveryWindowSize();
        });
        inTheRealGame(context);
    }

    // ---------------------------------------------------------------- the model

    private static CatalogueState.Lookup lookedAt(CatalogueState state) {
        return Objects.requireNonNull(state.lookup(), "an item is looked up");
    }

    private static Screen shown(Minecraft client) {
        return Objects.requireNonNull(client.gui.screen(), "a screen is open");
    }

    private static CataloguePanel panelOf(Screen screen) {
        return Objects.requireNonNull(
                CatalogueScreens.panelOf(screen), () -> screen.getClass().getSimpleName() + " has a catalogue");
    }

    private static PartsCatalogue fixtures() {
        return PartsCatalogue.of(CatalogueFixtures.book(), ContextMap.EMPTY);
    }

    private static void onlyTheKnownAreListed() {
        PartsCatalogue catalogue = fixtures();
        require(
                Set.copyOf(catalogue.items()).equals(Set.copyOf(CatalogueFixtures.items())),
                "the known items are what the four known recipes make or take, got " + catalogue.items());
        require(!catalogue.items().contains(Items.DIAMOND_SWORD), "an item no known recipe touches is not listed");
        require(
                !catalogue.items().contains(Items.CRAFTING_TABLE),
                "a station is not an ingredient or a result, so it is not listed");

        // A book that lacks the furnace recipe and the rotor hub does not list raw iron or the hub: nothing leaks from
        // a recipe it does not hold.
        ClientRecipeBook partial = new ClientRecipeBook();
        partial.add(CatalogueFixtures.entries().getFirst());
        partial.rebuildCollections();
        PartsCatalogue some = PartsCatalogue.of(partial, ContextMap.EMPTY);
        require(
                !some.items().contains(Items.RAW_IRON) && !some.items().contains(HangarParts.ROTOR_HUB),
                "a recipe the book lacks lists nothing, got " + some.items());
        require(
                some.items().contains(DRILL) && some.items().contains(Items.REDSTONE),
                "the one known recipe is listed");
        require(
                PartsCatalogue.of(new ClientRecipeBook(), ContextMap.EMPTY)
                        .items()
                        .isEmpty(),
                "an empty book lists nothing");
        require(
                catalogue.items().subList(0, 2).stream()
                        .allMatch(item -> BuiltInRegistries.ITEM
                                .getKey(item)
                                .getNamespace()
                                .equals(DeepCharter.MOD_ID)),
                "Company parts come first, got " + catalogue.items());
    }

    private static void theSearchMatchesNamesIdsAndNamespaces() {
        require(CatalogueSearch.matches("", DRILL), "an empty search matches everything");
        require(CatalogueSearch.matches("drill", DRILL), "a word matches inside the name");
        require(CatalogueSearch.matches("DRILL", DRILL), "case does not matter");
        require(CatalogueSearch.matches("drill head", DRILL), "every word matches, in any order");
        require(CatalogueSearch.matches("head drill", DRILL), "every word matches, in any order");
        require(!CatalogueSearch.matches("drill rotor", DRILL), "one word that does not match fails the item");
        require(CatalogueSearch.matches("iron_ingot", Items.IRON_INGOT), "a word matches the id's path");
        require(CatalogueSearch.matches("@deepcharter", DRILL), "@ matches the namespace");
        require(!CatalogueSearch.matches("@deepcharter", Items.IRON_INGOT), "a vanilla item is not in our namespace");
        require(CatalogueSearch.matches("@mine drill", Items.IRON_INGOT) == false, "@ and a word both have to match");
        List<Item> found = fixtures().items().stream()
                .filter(item -> CatalogueSearch.matches("iron", item))
                .toList();
        require(
                Set.copyOf(found).equals(Set.of(Items.IRON_INGOT, Items.RAW_IRON)),
                "'iron' finds the ingot and the raw iron, got " + found);
    }

    private static StackedItemContents holding(ItemStack... stacks) {
        StackedItemContents contents = new StackedItemContents();
        for (ItemStack stack : stacks) {
            contents.accountStack(stack);
        }
        return contents;
    }

    private static void craftableNowNeedsTheIngredientsInHand() {
        PartsCatalogue catalogue = fixtures();
        CatalogueState state = new CatalogueState();
        state.toggleCraftableOnly();
        require(
                state.shown(catalogue, holding(), TABLE).isEmpty(),
                "with nothing in hand nothing is craftable now: " + state.shown(catalogue, holding(), TABLE));
        List<Item> twoIron = state.shown(catalogue, holding(new ItemStack(Items.IRON_INGOT, 2)), TABLE);
        require(twoIron.equals(List.of(DRILL)), "two iron make the drill head the shapeless way, got " + twoIron);
        List<Item> hub = state.shown(
                catalogue, holding(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.COPPER_INGOT, 2)), TABLE);
        require(hub.equals(List.of(HangarParts.ROTOR_HUB)), "an iron and two copper make the rotor hub, got " + hub);
        List<Item> raw =
                state.shown(catalogue, holding(new ItemStack(Items.RAW_IRON)), new Bench(RecipeBookType.FURNACE, 1, 1));
        require(raw.equals(List.of(Items.IRON_INGOT)), "raw iron smelts to the ingot, got " + raw);
        state.toggleCraftableOnly();
        require(
                state.shown(catalogue, holding(), TABLE).size()
                        == catalogue.items().size(),
                "with the filter off everything known is listed");
        state.setQuery("rotor");
        require(
                state.shown(catalogue, holding(), TABLE).equals(List.of(HangarParts.ROTOR_HUB)),
                "the search narrows the list");
    }

    private static void craftableNowMeansCraftableOnThisScreen() {
        PartsCatalogue shapedOnly =
                PartsCatalogue.of(List.of(CatalogueFixtures.entries().getFirst()), ContextMap.EMPTY);
        StackedItemContents enough = holding(
                new ItemStack(Items.IRON_INGOT, 6), new ItemStack(Items.REDSTONE), new ItemStack(Items.COPPER_INGOT));
        require(shapedOnly.craftable(DRILL, enough, TABLE), "the 3 x 3 drill head is craftable at a crafting table");
        require(!shapedOnly.craftable(DRILL, enough, POCKET), "but not in the inventory's 2 x 2 grid");
        require(
                !shapedOnly.craftable(DRILL, enough, new Bench(RecipeBookType.FURNACE, 1, 1)),
                "a furnace screen does not craft it either");
        PartsCatalogue all = fixtures();
        require(
                all.craftable(
                        HangarParts.ROTOR_HUB,
                        holding(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.COPPER_INGOT, 2)),
                        POCKET),
                "a shapeless recipe of three ingredients fits the 2 x 2 grid");
        require(
                !all.craftable(Items.IRON_INGOT, holding(new ItemStack(Items.RAW_IRON)), TABLE),
                "a smelting recipe is not made at a crafting table");
        require(
                all.craftable(
                        Items.IRON_INGOT,
                        holding(new ItemStack(Items.RAW_IRON)),
                        new Bench(RecipeBookType.FURNACE, 1, 1)),
                "but is in a furnace");
    }

    private static void theLookupListsRecipesAndUsesAndStepsThrough() {
        PartsCatalogue catalogue = fixtures();
        CatalogueState state = new CatalogueState();
        require(state.lookup() == null && state.found(catalogue).isEmpty(), "nothing is looked up at first");
        state.look(catalogue, DRILL, CatalogueState.Mode.RECIPES);
        require(state.found(catalogue).size() == 2, "the drill head has two known recipes");
        require(state.lookup() != null && lookedAt(state).index() == 0, "the first recipe is shown first");
        state.look(catalogue, DRILL, CatalogueState.Mode.RECIPES);
        require(lookedAt(state).index() == 1, "R on the same item steps to the next recipe");
        state.look(catalogue, DRILL, CatalogueState.Mode.RECIPES);
        require(lookedAt(state).index() == 0, "and wraps round");
        state.step(catalogue, -1);
        require(lookedAt(state).index() == 1, "stepping back wraps the other way");
        state.look(catalogue, Items.IRON_INGOT, CatalogueState.Mode.USES);
        require(
                state.found(catalogue).size() == 3 && lookedAt(state).index() == 0,
                "the iron ingot is used in three known recipes, from the first, got "
                        + state.found(catalogue).size());
        for (var recipe : state.found(catalogue)) {
            require(recipe.ingredientItems().contains(Items.IRON_INGOT), "every use of the ingot takes the ingot");
        }
        state.look(catalogue, Items.RAW_IRON, CatalogueState.Mode.RECIPES);
        require(
                state.found(catalogue).isEmpty() && state.shownRecipe(catalogue) == null,
                "raw iron is made by no known recipe, so a lookup finds none (and invents none)");
        state.look(catalogue, Items.DIAMOND_SWORD, CatalogueState.Mode.RECIPES);
        require(state.shownRecipe(catalogue) == null, "an item no known recipe touches shows no recipe");
        var drill = catalogue.recipesFor(DRILL).getFirst();
        require(
                drill.columns() == 3 && drill.rows() == 3 && drill.cells().size() == 9,
                "the shaped recipe keeps its 3 x 3 grid");
        require(drill.cells().get(7).isEmpty(), "the blank cell of the pattern is blank");
        require(drill.station().equals("CRAFTING TABLE"), "the station is named in capitals, got " + drill.station());
        require(
                drill.needs().size() == 3
                        && drill.needs().getFirst().count() == 6
                        && drill.needs().getFirst().choices().getFirst().is(Items.IRON_INGOT)
                        && drill.needs().get(1).choices().getFirst().is(Items.REDSTONE),
                "six cells take iron, then one redstone and one copper in grid order, got " + drill.needs());
        var smelt = catalogue.recipesFor(Items.IRON_INGOT).getFirst();
        require(smelt.columns() == 1 && smelt.rows() == 1, "a smelting recipe is one cell");
    }

    private static void pagesStopAtTheEnds() {
        require(CatalogueState.pages(0, 25) == 1, "no items is one page");
        require(CatalogueState.pages(25, 25) == 1, "a full page is one page");
        require(CatalogueState.pages(26, 25) == 2, "one over is two pages");
        CatalogueState state = new CatalogueState();
        state.turn(5, 2);
        require(state.page(2) == 1, "the last page is the last");
        state.turn(-5, 2);
        require(state.page(2) == 0, "the first page is the first");
        state.turn(1, 2);
        state.setQuery("a");
        require(state.page(2) == 0, "a new search starts on the first page");
        state.turn(1, 2);
        state.toggleCraftableOnly();
        require(state.page(2) == 0, "the filter starts on the first page too");
    }

    private static void favouritesAreKeptPerPlayerAndSurviveReopening() {
        Path dir = temporaryDirectory();
        Path file = dir.resolve("favourites.json");
        try {
            UUID alex = UUID.randomUUID();
            UUID steve = UUID.randomUUID();
            CatalogueFavourites favourites = CatalogueFavourites.in(file);
            require(favourites.items(alex).isEmpty(), "no favourites at first");
            require(favourites.toggle(alex, DRILL), "the first toggle stars the item");
            require(favourites.toggle(alex, Items.IRON_INGOT), "a second item is starred");
            require(favourites.items(steve).isEmpty(), "another player does not share them");
            CatalogueFavourites again = CatalogueFavourites.in(file);
            require(
                    again.items(alex).equals(List.of(DRILL, Items.IRON_INGOT)),
                    "a new reading of the file keeps them, in the order starred, got " + again.items(alex));
            require(again.items(alex).contains(DRILL) && !again.items(steve).contains(DRILL), "the file is per player");
            require(!again.toggle(alex, DRILL), "the second toggle takes the star off");
            require(
                    CatalogueFavourites.in(file).items(alex).equals(List.of(Items.IRON_INGOT)),
                    "and that is saved too");
            // An id that names no item, or does not parse, is kept as written; two clients on one file keep each
            // other's stars.
            UUID jo = UUID.randomUUID();
            Files.writeString(
                    file,
                    "{\"version\": 1, \"players\": {\"" + jo + "\": [\"not an id\", \"minecraft:no_such_item\"]}}");
            CatalogueFavourites first = CatalogueFavourites.in(file);
            CatalogueFavourites second = CatalogueFavourites.in(file);
            require(first.items(jo).isEmpty(), "ids that name no item are not listed");
            first.toggle(jo, DRILL);
            second.toggle(steve, Items.IRON_INGOT);
            String written = Files.readString(file);
            require(
                    written.contains("not an id") && written.contains("minecraft:no_such_item"),
                    "ids it cannot use are kept in the file, got " + written);
            CatalogueFavourites both = CatalogueFavourites.in(file);
            require(
                    both.items(jo).equals(List.of(DRILL)) && both.items(steve).equals(List.of(Items.IRON_INGOT)),
                    "the second client's write kept the first client's star, got " + written);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(file);
            delete(dir);
        }
    }

    private static void aFavouritesFileOfAnotherVersionIsNeverWritten() {
        Path dir = temporaryDirectory();
        Path file = dir.resolve("favourites.json");
        try {
            for (String content : List.of("{\"version\": 2, \"players\": {}}", "this is not json")) {
                Files.writeString(file, content);
                CatalogueFavourites favourites = CatalogueFavourites.in(file);
                UUID alex = UUID.randomUUID();
                require(favourites.toggle(alex, DRILL), "a favourite still works in memory over: " + content);
                require(favourites.items(alex).contains(DRILL), "and is held in memory over: " + content);
                require(
                        Files.readString(file).equals(content),
                        "the file is left as another build wrote it: " + content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(file);
            delete(dir);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("deepcharter-catalogue");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- the layout

    private static Optional<CatalogueLayout> layoutAt(int width, int height) {
        return Optional.ofNullable(CatalogueLayout.of(width, height, CatalogueLook.current(), CrtTuning.current(), 9));
    }

    private static void thePanelsFitAtEveryWindowSize() {
        require(
                layoutAt(WIDTH, HEIGHT).isPresent(),
                "the panels draw at the default window, " + WIDTH + " by " + HEIGHT);
        require(layoutAt(NARROWEST, HEIGHT).isPresent(), "the panels draw at " + NARROWEST + " wide");
        require(layoutAt(NARROWEST - 1, HEIGHT).isEmpty(), "and are hidden, not clipped, one pixel narrower");
        require(layoutAt(320, 240).isEmpty(), "they are hidden at the game's smallest GUI, 320 by 240");
        require(
                layoutAt(WIDTH, 190).isEmpty(),
                "and hidden when the window is too short for " + CatalogueLayout.MIN_ROWS + " rows of items");
        for (int height : new int[] {200, 215, HEIGHT, 270}) {
            for (int width : new int[] {NARROWEST, 426, WIDTH, 480, 854}) {
                requireFits(layoutAt(width, height).orElseThrow(), width, height);
            }
        }
    }

    private static void requireFits(CatalogueLayout layout, int width, int height) {
        CatalogueLook look = CatalogueLook.current();
        String at = " at " + width + " by " + height;
        for (Rect panel : List.of(layout.catalogue(), layout.recipe())) {
            require(inside(panel, new Rect(0, 0, width, height)), "a panel " + panel + " leaves the screen" + at);
        }
        require(layout.recipe().right() <= layout.inventory().x(), "the recipe panel overlaps the inventory" + at);
        require(layout.catalogue().x() >= layout.inventory().right(), "the catalogue overlaps the inventory" + at);
        Rect glass = layout.catalogueGlass();
        CatalogueLayout.Shelf shelf = layout.shelf();
        for (Rect rect : List.of(shelf.search(), shelf.craftable(), shelf.previous(), shelf.next())) {
            require(inside(rect, glass), "a control " + rect + " leaves the catalogue's glass " + glass + at);
        }
        Rect grid = new Rect(
                shelf.gridX(),
                shelf.gridY(),
                (shelf.columns() - 1) * look.gridCell() + look.slotSize(),
                (shelf.rows() - 1) * look.gridCell() + look.slotSize());
        require(inside(grid, glass), "the item grid " + grid + " leaves the glass " + glass + at);
        require(grid.bottom() <= shelf.favouritesLabelY(), "the grid runs into the favourites label" + at);
        require(
                shelf.favouritesY() + look.slotSize() <= shelf.craftable().y(),
                "the favourites row runs into the craftable toggle" + at);
        require(shelf.craftable().bottom() <= shelf.previous().y(), "the toggle runs into the page buttons" + at);
        require(shelf.columns() == 5, "the grid is 5 wide as in the mockup" + at);
        require(
                shelf.rows() >= CatalogueLayout.MIN_ROWS && (height < HEIGHT || shelf.rows() == 5),
                "the grid is 5 rows at the default height and at least 3 in a shorter window, got " + shelf.rows()
                        + at);
        CatalogueLayout.Card card = layout.card();
        require(inside(card.spec(), layout.recipeGlass()), "the spec sheet button leaves the recipe glass" + at);
        require(card.needRows() >= 1 && (height < HEIGHT || card.needRows() >= 3), "the have/need rows fit" + at);
        int gridBottom = card.gridY() + 3 * look.recipeCell();
        require(gridBottom <= card.spec().y(), "the recipe grid runs into the spec sheet button" + at);
        int resultRight = card.gridX() + look.resultX() + look.slotSize();
        require(resultRight <= card.right(), "the result cell leaves the recipe panel's text area" + at);
    }

    private static boolean inside(Rect inner, Rect outer) {
        return inner.x() >= outer.x()
                && inner.y() >= outer.y()
                && inner.right() <= outer.right()
                && inner.bottom() <= outer.bottom();
    }

    // ---------------------------------------------------------------- the real game

    private static void inTheRealGame(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player's inventory", client -> client.player != null && client.level != null);
            context.runOnClient(client ->
                    CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook()));
            CatalogueScene.openInventory(context);
            // Reopens the inventory, so it comes before the screen and the panel the rest hold are taken.
            theRecipeBookButtonIsGone(context);
            AbstractContainerScreen<?> screen = screen(context);
            CataloguePanel panel = CatalogueScene.panel(context);

            theCatalogueListsOnlyWhatTheBookKnows(context, panel);
            everyStringIsInsideItsGlass(context, screen, "with nothing looked up");
            rAndUShowRecipesAndUsesOfAHoveredItem(context, screen, panel);
            everyStringIsInsideItsGlass(context, screen, "with a recipe shown");
            hoveringAnInventorySlotWorksToo(context, singleplayer, screen, panel);
            theRecipePanelCanBePutAway(context, screen, panel);
            theSpecSheetButtonCallsItsHook(context, panel);
            theSearchFiltersAndKeepsItsKeys(context, screen, panel);
            favouritesStarAndPersist(context, screen, panel);
            craftableNowFiltersByWhatIsHeld(context, singleplayer, screen, panel);
            thePanelsFollowTheWindowSize(context, screen);
            theCatalogueOpensAtAnItem(context);
            theCraftingTableHasTheCatalogueToo(context);

            context.setScreen(() -> null);
            context.runOnClient(client ->
                    CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook()));
        }
    }

    private static AbstractContainerScreen<?> screen(ClientGameTestContext context) {
        return context.computeOnClient(client -> (AbstractContainerScreen<?>) client.gui.screen());
    }

    private static void theRecipeBookButtonIsGone(ClientGameTestContext context) {
        context.runOnClient(client -> {
            List<AbstractWidget> buttons = new ArrayList<>(Screens.getWidgets(shown(client)));
            require(
                    buttons.stream().anyMatch(ImageButton.class::isInstance),
                    "the vanilla recipe book button is still in the screen, hidden, not gone: this check needs it");
            for (AbstractWidget widget : buttons) {
                require(
                        !(widget instanceof ImageButton) || (!widget.visible && !widget.active),
                        "the vanilla recipe book button is hidden and cannot be pressed");
            }
        });
        // A player whose recipe book was left open must not get an inventory pushed aside for a book they cannot close.
        context.runOnClient(
                client -> ClientSession.player(client).getRecipeBook().setOpen(RecipeBookType.CRAFTING, true));
        context.setScreen(() -> null);
        CatalogueScene.openInventory(context);
        context.runOnClient(client -> {
            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) shown(client);
            Rect inventory = Objects.requireNonNull(panelOf(screen).layout()).inventory();
            require(
                    !ClientSession.player(client).getRecipeBook().isOpen(RecipeBookType.CRAFTING),
                    "opening the inventory closes a recipe book left open");
            require(
                    inventory.x() == leftPos(screen) && inventory.y() == topPos(screen),
                    "the inventory stands where the layout assumes it, " + inventory + " against " + leftPos(screen)
                            + "," + topPos(screen));
        });
    }

    private static int leftPos(AbstractContainerScreen<?> screen) {
        return ContainerScreenFields.intField(screen, "leftPos");
    }

    private static int topPos(AbstractContainerScreen<?> screen) {
        return ContainerScreenFields.intField(screen, "topPos");
    }

    private static void theCatalogueListsOnlyWhatTheBookKnows(ClientGameTestContext context, CataloguePanel panel) {
        context.runOnClient(client -> {
            List<Item> listed = panel.catalogue().items();
            require(
                    listed.containsAll(CatalogueFixtures.items()),
                    "the catalogue lists the items of the known recipes " + CatalogueFixtures.items() + ", it lists "
                            + listed);
            require(!listed.contains(Items.DIAMOND_SWORD), "an item no known recipe touches is not listed");
            Set<Item> book = new HashSet<>();
            ClientSession.player(client)
                    .getRecipeBook()
                    .getCollections()
                    .forEach(collection -> collection.getRecipes().forEach(entry -> {
                        // Everything the book holds is listed, and nothing else: its results and ingredients are all
                        // that is.
                        book.addAll(PartsCatalogue.of(
                                        List.of(entry),
                                        SlotDisplayContext.fromLevel(Objects.requireNonNull(client.level)))
                                .items());
                    }));
            require(Set.copyOf(listed).equals(book), "the catalogue lists exactly what the book's recipes touch");
        });
    }

    /** Draws the screen and checks the text of the panels against the glass it must lie in. */
    private static void everyStringIsInsideItsGlass(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, String when) {
        // A hovered item's name is drawn and recorded too, and sits in no glass: nothing is hovered while the text is
        // read.
        CatalogueScene.parkCursor(
                context, CatalogueScene.layout(context, context.computeOnClient(client -> panelOf(screen))));
        context.runOnClient(client -> {
            CataloguePanel panel = panelOf(screen);
            CatalogueLayout layout = Objects.requireNonNull(panel.layout(), () -> "the panels are laid out " + when);
            List<Drawn> drawn = drawn(client, screen);
            List<String> texts = drawn.stream().map(Drawn::text).toList();
            boolean hintShown = panel.state().query().isEmpty() && !panel.searchFocused();
            Rect catalogue = layout.catalogueGlass();
            Rect recipe = layout.recipeGlass();
            Rect inventory = layout.inventory();
            for (Drawn text : drawn) {
                Rect box = new Rect(
                        text.box().x(),
                        text.box().y(),
                        text.box().width(),
                        text.box().height());
                require(
                        inside(box, catalogue) || inside(box, recipe) || inside(box, inventory),
                        "text '" + text.shortText() + "' at " + box + " is in no glass and not in the inventory, "
                                + when + " (catalogue glass " + catalogue + ", recipe glass " + recipe + ")");
            }
            require(
                    texts.stream().anyMatch(text -> text.matches("\\d+/\\d+")),
                    "the catalogue draws its page counter " + when + ", it draws " + texts);
            for (String expected : List.of("PARTS CATALOGUE", "FAVOURITES", "CRAFTABLE NOW")) {
                require(
                        texts.contains(expected),
                        "the catalogue draws '" + expected + "' " + when + ", it draws " + texts);
            }
            require(texts.contains("SEARCH") == hintShown, "the search field shows its hint only while empty " + when);
            CatalogueLayout.Shelf shelf = layout.shelf();
            require(
                    labelInside(drawn, "CRAFTABLE NOW", shelf.craftable()),
                    "the label of the craftable toggle lies inside the toggle " + when);
            require(
                    labelInside(drawn, "<", shelf.previous()) && labelInside(drawn, ">", shelf.next()),
                    "the page arrows lie in their buttons " + when);
            if (panel.showsRecipePanel()) {
                require(
                        texts.contains("SPEC SHEET"),
                        "the recipe panel draws its SPEC SHEET button " + when + ", it draws " + texts);
                require(
                        labelInside(drawn, "SPEC SHEET", layout.card().spec()),
                        "the spec sheet label lies inside its button " + when);
                KnownRecipe shownRecipe = panel.state().shownRecipe(panel.catalogue());
                if (shownRecipe != null) {
                    require(
                            texts.contains(shownRecipe.station()),
                            "the recipe panel names the station '" + shownRecipe.station() + "' " + when + ", it draws "
                                    + texts);
                }
            } else {
                require(!texts.contains("SPEC SHEET"), "no recipe panel is drawn before an item is looked up");
            }
        });
    }

    /** What the screen draws with the mouse where it really is, so the catalogue keeps the hover it has. */
    private static List<Drawn> drawn(Minecraft client, Screen screen) {
        var window = client.getWindow();
        return DrawnText.of(screen, (int) client.mouseHandler.getScaledXPos(window), (int)
                client.mouseHandler.getScaledYPos(window));
    }

    private static List<String> texts(Minecraft client, Screen screen) {
        return drawn(client, screen).stream().map(Drawn::text).toList();
    }

    private static boolean labelInside(List<Drawn> drawn, String label, Rect button) {
        for (Drawn text : drawn) {
            if (text.text().equals(label)) {
                Rect box = new Rect(
                        text.box().x(),
                        text.box().y(),
                        text.box().width(),
                        text.box().height());
                if (inside(box, button)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void rAndUShowRecipesAndUsesOfAHoveredItem(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, CataloguePanel panel) {
        // The server may have taught more recipes of these items meanwhile: the counters below count the fixtures
        // alone.
        context.runOnClient(
                client -> CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook()));
        context.waitTicks(2);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, DRILL)));
        require(
                context.computeOnClient(client -> panel.hoveredItem()) == DRILL,
                "the cursor is on the drill head's cell");
        List<Drawn> named = context.computeOnClient(client -> drawn(client, screen));
        Drawn strip = named.stream()
                .filter(text -> text.text().equals("DRILL HEAD"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("a hovered cell is named in the terminal strip, drew " + named));
        require(
                inside(
                        new Rect(
                                strip.box().x(),
                                strip.box().y(),
                                strip.box().width(),
                                strip.box().height()),
                        new Rect(0, 0, WIDTH, HEIGHT)),
                "the name strip lies inside the screen");
        // The top right cell, against the screen's right edge: the strip is kept inside the screen.
        CatalogueScene.hover(
                context, CatalogueScene.gridCell(layout, layout.shelf().columns() - 1));
        for (Drawn text : context.computeOnClient(client -> drawn(client, screen))) {
            require(
                    inside(
                            new Rect(
                                    text.box().x(),
                                    text.box().y(),
                                    text.box().width(),
                                    text.box().height()),
                            new Rect(0, 0, WIDTH, HEIGHT)),
                    "text '" + text.shortText() + "' leaves the screen with the cursor on the last column");
        }
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, DRILL)));
        require(!context.computeOnClient(client -> panel.showsRecipePanel()), "no recipe panel until R or U");
        context.getInput().pressKey(InputConstants.KEY_R);
        context.waitTicks(1);
        CatalogueState.Lookup lookup =
                context.computeOnClient(client -> panel.state().lookup());
        require(
                lookup != null && lookup.item() == DRILL && lookup.mode() == CatalogueState.Mode.RECIPES,
                "R on a hovered cell shows the recipes of its item, got " + lookup);
        require(context.computeOnClient(client -> panel.showsRecipePanel()), "the recipe panel is drawn");
        require(
                context.computeOnClient(client -> texts(client, screen)).contains("R 1/2"),
                "the panel says it shows the first of two recipes");
        context.getInput().pressKey(InputConstants.KEY_R);
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> lookedAt(panel.state()).index()) == 1,
                "R again steps to the second recipe");
        require(context.computeOnClient(client -> texts(client, screen)).contains("R 2/2"), "and the counter follows");

        CatalogueScene.hover(
                context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, Items.IRON_INGOT)));
        context.getInput().pressKey(InputConstants.KEY_U);
        context.waitTicks(1);
        lookup = context.computeOnClient(client -> panel.state().lookup());
        require(
                lookup != null && lookup.item() == Items.IRON_INGOT && lookup.mode() == CatalogueState.Mode.USES,
                "U shows the uses of the hovered item, got " + lookup);
        require(
                context.computeOnClient(client -> texts(client, screen)).contains("U 1/3"),
                "the iron ingot is used by three known recipes");

        // The recipe panel's own cells hover too: the redstone in a recipe shows its recipes (none known) on R.
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
        context.waitTicks(1);
        CatalogueLayout.Card card = layout.card();
        Rect firstCell = new Rect(card.gridX(), card.gridY(), 18, 18);
        CatalogueScene.hover(context, firstCell);
        require(
                context.computeOnClient(client -> panel.hoveredItem()) == Items.IRON_INGOT,
                "hovering a cell of the shown recipe finds its item");
        context.getInput().pressKey(InputConstants.KEY_U);
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> lookedAt(panel.state()).item()) == Items.IRON_INGOT,
                "U on a cell of the recipe panel looks that item up");
        // A click does the same without the keys: left for recipes, right for uses.
        CatalogueScene.hover(
                context,
                CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, HangarParts.ROTOR_HUB)));
        context.getInput().pressMouse(LEFT_MOUSE);
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> lookedAt(panel.state()).item()) == HangarParts.ROTOR_HUB,
                "a left click on a cell shows its recipes");
        CatalogueScene.hover(
                context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, Items.COPPER_INGOT)));
        context.getInput().pressMouse(RIGHT_MOUSE);
        context.waitTicks(1);
        CatalogueState.Lookup clicked = context.computeOnClient(client -> lookedAt(panel.state()));
        require(clicked.mode() == CatalogueState.Mode.USES, "a right click shows its uses");
        require(context.computeOnClient(client -> client.gui.screen()) == screen, "no click closed the screen");
        // The result cell of a lookup and an unknown item: nothing is made up.
        context.runOnClient(
                client -> panel.state().look(panel.catalogue(), Items.DIAMOND_SWORD, CatalogueState.Mode.RECIPES));
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> texts(client, screen)).contains("NO KNOWN RECIPE"),
                "an item with no known recipe says so");
        require(
                context.computeOnClient(client -> texts(client, screen)).contains("DIAMOND SWORD"),
                "and the panel is titled with the item");
    }

    private static void theSpecSheetButtonCallsItsHook(ClientGameTestContext context, CataloguePanel panel) {
        List<Item> opened = new ArrayList<>();
        context.runOnClient(client -> {
            SpecSheetHook.set(opened::add);
            panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES);
        });
        context.waitTicks(1);
        try {
            CatalogueLayout layout = CatalogueScene.layout(context, panel);
            CatalogueScene.hover(context, layout.card().spec());
            context.getInput().pressMouse(LEFT_MOUSE);
            context.waitTicks(1);
            require(opened.equals(List.of(DRILL)), "SPEC SHEET calls its hook with the item looked up, got " + opened);
        } finally {
            context.runOnClient(client -> SpecSheetHook.set(item -> {}));
        }
    }

    private static void theRecipePanelCanBePutAway(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, CataloguePanel panel) {
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
        context.waitTicks(1);
        require(context.computeOnClient(client -> panel.showsRecipePanel()), "the recipe panel is up");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(1);
        require(!context.computeOnClient(client -> panel.showsRecipePanel()), "Escape puts the recipe panel away");
        require(
                context.computeOnClient(client -> client.gui.screen()) == screen,
                "and does not close the inventory the first time");
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
        context.waitTicks(1);
        CatalogueLayout.Card card = CatalogueScene.layout(context, panel).card();
        CatalogueScene.hover(context, new Rect(card.left(), card.titleY(), 20, 9));
        context.getInput().pressMouse(LEFT_MOUSE);
        context.waitTicks(1);
        require(
                !context.computeOnClient(client -> panel.showsRecipePanel()),
                "a click on the recipe panel's title puts it away");
        require(context.computeOnClient(client -> client.gui.screen()) == screen, "and the inventory stays open");
    }

    private static void theCatalogueOpensAtAnItem(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.waitTicks(2);
        context.runOnClient(client -> CatalogueScreens.open(DRILL));
        ClientWait.screen(context, InventoryScreen.class);
        context.waitTicks(3);
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueState.Lookup lookup = context.computeOnClient(client -> lookedAt(panel.state()));
        require(
                lookup.item() == DRILL && lookup.mode() == CatalogueState.Mode.RECIPES,
                "open(item) opens the inventory at that item's recipes, got " + lookup);
        context.runOnClient(client -> CatalogueScreens.open(Items.IRON_INGOT));
        require(
                context.computeOnClient(client -> lookedAt(panel.state()).item()) == Items.IRON_INGOT,
                "and with the inventory open it shows the next item without a new screen");
    }

    private static void hoveringAnInventorySlotWorksToo(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            AbstractContainerScreen<?> screen,
            CataloguePanel panel) {
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 1");
        ClientWait.until(
                context,
                "an iron ingot in the inventory",
                client -> ClientSession.player(client).getInventory().contains(new ItemStack(Items.IRON_INGOT)));
        context.waitTicks(2);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        Rect slot = context.computeOnClient(client -> {
            for (var candidate : screen.getMenu().slots) {
                if (candidate.hasItem() && candidate.getItem().is(Items.IRON_INGOT)) {
                    return new Rect(
                            layout.inventory().x() + candidate.x,
                            layout.inventory().y() + candidate.y,
                            16,
                            16);
                }
            }
            throw new AssertionError("no slot holds the iron ingot");
        });
        CatalogueScene.hover(context, slot);
        require(
                context.computeOnClient(client -> panel.hoveredItem()) == Items.IRON_INGOT,
                "the cursor on an inventory slot finds its item");
        context.getInput().pressKey(InputConstants.KEY_R);
        context.waitTicks(1);
        CatalogueState.Lookup lookup =
                context.computeOnClient(client -> panel.state().lookup());
        require(
                lookup != null && lookup.item() == Items.IRON_INGOT && lookup.mode() == CatalogueState.Mode.RECIPES,
                "R on a hovered inventory slot shows that item's recipes, got " + lookup);
        require(
                context.computeOnClient(client -> client.gui.screen()) == screen,
                "R did not close or change the screen");
    }

    private static void theSearchFiltersAndKeepsItsKeys(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, CataloguePanel panel) {
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        Rect search = layout.shelf().search();
        CatalogueScene.hover(context, search);
        context.getInput().pressMouse(LEFT_MOUSE);
        context.waitTicks(1);
        require(context.computeOnClient(client -> panel.searchFocused()), "a click on the search field focuses it");
        context.getInput().typeChars("rotor");
        context.waitTicks(1);
        require(context.computeOnClient(client -> panel.state().query()).equals("rotor"), "typing fills the search");
        require(
                context.computeOnClient(client -> panel.shownItems()).equals(List.of(HangarParts.ROTOR_HUB)),
                "the search lists only the rotor hub");
        // While the field has the keyboard, the screen's own keys are not its: E would close the inventory.
        context.getInput().pressKey(InputConstants.KEY_E);
        context.getInput().pressKey(InputConstants.KEY_R);
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> client.gui.screen()) == screen,
                "E in the search field does not close the inventory");
        context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> panel.state().query()).equals("roto"),
                "backspace takes a letter off");
        everyStringIsInsideItsGlass(context, screen, "with a search typed");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(1);
        require(!context.computeOnClient(client -> panel.searchFocused()), "escape leaves the search field");
        require(context.computeOnClient(client -> client.gui.screen()) == screen, "and does not close the inventory");
        // A search too long to show is cut from the front, so the end that is typed stays on screen.
        CatalogueScene.hover(context, search);
        context.getInput().pressMouse(LEFT_MOUSE);
        context.getInput().typeChars("a very long search that cannot fit the field");
        context.waitTicks(1);
        everyStringIsInsideItsGlass(context, screen, "with a long search typed");
        require(
                context.computeOnClient(client -> panel.state().query().length())
                        <= CatalogueLook.current().maxQueryLength(),
                "the search is held to its longest");
        require(
                context.computeOnClient(client -> panel.shownItems()).isEmpty(),
                "a search nothing matches lists nothing");
        everyStringIsInsideItsGlass(context, screen, "with nothing found");
        context.getInput().holdControl();
        context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
        context.getInput().releaseControl();
        context.waitTicks(1);
        require(context.computeOnClient(client -> panel.state().query()).isEmpty(), "ctrl+backspace clears the search");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(1);
    }

    private static void favouritesStarAndPersist(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, CataloguePanel panel) {
        UUID player =
                context.computeOnClient(client -> ClientSession.player(client).getUUID());
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        try {
            CatalogueScene.hover(
                    context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, DRILL)));
            context.getInput().pressKey(InputConstants.KEY_A);
            context.waitTicks(1);
            require(
                    CatalogueFavourites.shared().items(player).contains(DRILL),
                    "A on a hovered cell stars its item for this player");
            Rect first = new Rect(layout.shelf().gridX(), layout.shelf().favouritesY(), 18, 18);
            CatalogueScene.hover(context, first);
            require(
                    context.computeOnClient(client -> panel.hoveredItem()) == DRILL,
                    "the favourites row shows the starred item and hovering it finds it");
            context.getInput().pressKey(InputConstants.KEY_A);
            context.waitTicks(1);
            require(!CatalogueFavourites.shared().items(player).contains(DRILL), "A on a favourite takes the star off");
            context.getInput().pressKey(InputConstants.KEY_A);
            context.waitTicks(1);
            require(
                    CatalogueFavourites.shared().items(player).isEmpty(),
                    "with the cursor off the row, A does nothing");
        } finally {
            if (CatalogueFavourites.shared().items(player).contains(DRILL)) {
                CatalogueFavourites.shared().toggle(player, DRILL);
            }
        }
        everyStringIsInsideItsGlass(context, screen, "after the favourites changed");
    }

    private static void craftableNowFiltersByWhatIsHeld(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            AbstractContainerScreen<?> screen,
            CataloguePanel panel) {
        singleplayer.getServer().runCommand("clear @p");
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 1");
        singleplayer.getServer().runCommand("give @p minecraft:copper_ingot 2");
        ClientWait.until(
                context,
                "an iron and two copper in the inventory",
                client -> ClientSession.player(client).getInventory().contains(new ItemStack(Items.COPPER_INGOT, 2)));
        context.waitTicks(2);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, layout.shelf().craftable());
        context.getInput().pressMouse(LEFT_MOUSE);
        context.waitTicks(1);
        List<Item> shown = context.computeOnClient(client -> panel.shownItems());
        require(
                context.computeOnClient(client -> panel.state().craftableOnly()),
                "a click on the toggle turns the filter on");
        require(shown.contains(HangarParts.ROTOR_HUB), "an iron and two copper make the rotor hub, got " + shown);
        require(!shown.contains(DRILL), "but not the drill head, which needs more iron, got " + shown);
        everyStringIsInsideItsGlass(context, screen, "with the craftable filter on");
        context.getInput().pressMouse(LEFT_MOUSE);
        context.waitTicks(1);
        require(!context.computeOnClient(client -> panel.state().craftableOnly()), "a second click turns it off");
    }

    private static void thePanelsFollowTheWindowSize(ClientGameTestContext context, AbstractContainerScreen<?> screen) {
        CataloguePanel panel = context.computeOnClient(client -> panelOf(screen));
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
        for (int width : new int[] {NARROWEST, WIDTH}) {
            context.runOnClient(client -> screen.resize(width, HEIGHT));
            context.waitTicks(2);
            everyStringIsInsideItsGlass(context, screen, "at " + width + " by " + HEIGHT);
            require(
                    context.computeOnClient(client -> leftPos(screen)) == (width - CatalogueLayout.IMAGE_WIDTH) / 2,
                    "the inventory image is centred at " + width);
        }
        for (int width : new int[] {NARROWEST - 1, 320}) {
            context.runOnClient(client -> screen.resize(width, HEIGHT));
            context.waitTicks(2);
            require(context.computeOnClient(client -> panel.layout() == null), "the panels are hidden at " + width);
            require(
                    context.computeOnClient(
                            client -> bookButtons(screen).stream().allMatch(button -> button.visible && button.active)),
                    "the vanilla recipe book button is back where the panels are hidden, at " + width);
            List<String> texts = context.computeOnClient(client -> texts(client, screen));
            require(
                    !texts.contains("PARTS CATALOGUE") && !texts.contains("SPEC SHEET"),
                    "a hidden catalogue draws nothing, drew " + texts);
            context.getInput().pressKey(InputConstants.KEY_R);
            require(
                    context.computeOnClient(client -> client.gui.screen()) == screen,
                    "the screen is as it was at " + width);
        }
        context.runOnClient(client -> screen.resize(WIDTH, HEIGHT));
        context.waitTicks(2);
        require(context.computeOnClient(client -> panel.layout() != null), "the panels return when the window grows");
        require(
                context.computeOnClient(
                        client -> bookButtons(screen).stream().noneMatch(button -> button.visible || button.active)),
                "and the recipe book button goes again");
    }

    private static List<AbstractWidget> bookButtons(Screen screen) {
        List<AbstractWidget> buttons = Screens.getWidgets(screen).stream()
                .filter(ImageButton.class::isInstance)
                .toList();
        require(!buttons.isEmpty(), "the screen has a vanilla recipe book button");
        return buttons;
    }

    private static void theCraftingTableHasTheCatalogueToo(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.runOnClient(client -> client.gui.setScreen(new CraftingScreen(
                new CraftingMenu(0, ClientSession.player(client).getInventory()),
                ClientSession.player(client).getInventory(),
                Component.translatable("container.crafting"))));
        ClientWait.screen(context, CraftingScreen.class);
        context.waitTicks(3);
        AbstractContainerScreen<?> table = screen(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
        context.waitTicks(1);
        require(context.computeOnClient(client -> panel.layout() != null), "the crafting table has the panels");
        everyStringIsInsideItsGlass(context, table, "on the crafting table");
        require(
                context.computeOnClient(client -> leftPos(table)) == (WIDTH - CatalogueLayout.IMAGE_WIDTH) / 2,
                "the crafting table stands where the layout assumes");
    }
}
