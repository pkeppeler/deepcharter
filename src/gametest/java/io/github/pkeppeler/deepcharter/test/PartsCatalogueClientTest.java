package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.GlassCorners.requireClearOfCorners;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPages;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
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
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetOpener;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.TextWrap;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtText;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Client GameTest for #846, the parts catalogue. The model (what is known, the search, craftable now, the lookup, the favourites file)
 * and the layout at each window size are checked first, once a world is loaded; they read no world state. Then the real game: the panels
 * beside the inventory, the hidden recipe-book button, R and U on a hovered item, the search, the favourites, the craftable filter, and
 * that every string the panels draw lies whole inside its panel's glass (a string past the glass is clipped away, and {@code DrawnText}
 * drops a string wholly outside its scissor, so a missing string fails too).
 */
public class PartsCatalogueClientTest implements FabricClientGameTest {
    private static final Item DRILL = HangarParts.DRILL_HEAD;
    /** The default window, 854 by 480 at GUI scale 2. */
    private static final int WIDTH = 427;

    private static final int HEIGHT = 240;
    /** The inventory's crafting grid is 2 x 2, the crafting table's 3 x 3. */
    private static final Bench TABLE = new Bench(RecipeBookType.CRAFTING, 3, 3);

    private static final Bench POCKET = new Bench(RecipeBookType.CRAFTING, 2, 2);
    /** Past the fixtures' ids (they start at 100,000), so the tag fixture never takes one's place. */
    private static final int TAG_FIXTURE_ID = 200_000;

    /** The narrowest GUI the panels draw at, and one pixel under it. */
    private static final int NARROWEST = 425;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
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

    /**
     * A tag ingredient lists only the items the campaign world yields (#1263, ADR 0093): "any planks" shows oak, which
     * salvage crates drop, and not jungle or pale oak, which the world has none of. Resolved with the world's tags, as
     * the real catalogue is.
     */
    private static void aTagIngredientListsOnlyObtainableItems(Minecraft client) {
        ClientLevel level = Objects.requireNonNull(client.level, "a world is loaded");
        ContextMap context = SlotDisplayContext.fromLevel(level);
        RecipeDisplayEntry chest = new RecipeDisplayEntry(
                new RecipeDisplayId(TAG_FIXTURE_ID),
                new ShapelessCraftingRecipeDisplay(
                        List.of(new SlotDisplay.TagSlotDisplay(level.registryAccess()
                                .lookupOrThrow(Registries.ITEM)
                                .getOrThrow(ItemTags.PLANKS))),
                        new SlotDisplay.ItemSlotDisplay(Items.CHEST),
                        new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)),
                OptionalInt.empty(),
                RecipeBookCategories.CRAFTING_MISC,
                Optional.empty());
        Set<Item> planks = KnownRecipe.of(chest, context).ingredientItems();
        require(planks.contains(Items.OAK_PLANKS), "any planks lists oak planks, got " + planks);
        require(
                !planks.contains(Items.JUNGLE_PLANKS) && !planks.contains(Items.PALE_OAK_PLANKS),
                "any planks lists no wood the world lacks, got " + planks);
        List<Item> listed = PartsCatalogue.of(List.of(chest), context).items();
        require(
                !listed.contains(Items.JUNGLE_PLANKS) && listed.contains(Items.OAK_PLANKS),
                "the catalogue lists oak planks and not jungle planks, got " + listed);
    }

    /**
     * The creative tabs, built as the creative screen builds them, hold no vanilla item the world lacks, and keep one
     * it yields.
     */
    private static void theCreativeTabsShowOnlyObtainableItems(Minecraft client) {
        ClientLevel level = Objects.requireNonNull(client.level, "a world is loaded");
        CreativeModeTab.ItemDisplayParameters parameters =
                new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true, level.registryAccess());
        Set<Item> shown = new HashSet<>();
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() == CreativeModeTab.Type.CATEGORY) {
                tab.buildContents(parameters);
                tab.getDisplayItems().forEach(stack -> shown.add(stack.getItem()));
                tab.getSearchTabDisplayItems().forEach(stack -> shown.add(stack.getItem()));
            }
        }
        require(shown.contains(Items.OAK_PLANKS), "the creative tabs show oak planks");
        require(
                !shown.contains(Items.JUNGLE_PLANKS) && !shown.contains(Items.DIAMOND),
                "the creative tabs show no jungle planks and no diamond");
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
        require(
                shapedOnly.craftable(DRILL, enough, POCKET),
                "and in the inventory's 2 x 2 grid too: what is in hand is what counts, the table is only where it is made");
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

    private static void craftableNowCountsEveryGrid() {
        PartsCatalogue shapedOnly =
                PartsCatalogue.of(List.of(CatalogueFixtures.entries().getFirst()), ContextMap.EMPTY);
        StackedItemContents enough = holding(
                new ItemStack(Items.IRON_INGOT, 6), new ItemStack(Items.REDSTONE), new ItemStack(Items.COPPER_INGOT));
        CatalogueState state = new CatalogueState();
        state.toggleCraftableOnly();
        require(
                state.shown(shapedOnly, enough, POCKET).equals(List.of(DRILL)),
                "a 3 x 3 recipe the inventory satisfies is listed at the 2 x 2 inventory, got "
                        + state.shown(shapedOnly, enough, POCKET));
        require(
                shapedOnly.recipesFor(DRILL).getFirst().needsTable(),
                "the 3 x 3 drill head needs the crafting table's grid");
        require(shapedOnly.tableOnly(DRILL, enough, POCKET), "and it is marked with the table icon at the inventory");
        PartsCatalogue all = fixtures();
        require(
                !all.tableOnly(DRILL, holding(new ItemStack(Items.IRON_INGOT, 2)), POCKET),
                "a drill head that the shapeless recipe makes in the 2 x 2 grid carries no table icon");
        require(
                !all.recipesFor(HangarParts.ROTOR_HUB).getFirst().needsTable(),
                "a three-ingredient shapeless recipe fits the 2 x 2 grid");
        require(
                state.craftableCount(shapedOnly, enough, POCKET) == 1,
                "one item is craftable now, got " + state.craftableCount(shapedOnly, enough, POCKET));
        require(state.craftableCount(all, holding(), POCKET) == 0, "nothing in hand makes nothing");
        StackedItemContents hub = holding(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.COPPER_INGOT, 2));
        require(state.craftableCount(all, hub, POCKET) == 1, "an iron and two copper make the rotor hub alone");
        state.setQuery("zzz");
        require(
                state.craftableCount(all, hub, POCKET) == 1,
                "the count is of the whole catalogue: the search does not change it");
        state.toggleCraftableOnly();
        require(state.craftableCount(all, hub, POCKET) == 1, "nor does the switch");
        require(
                state.craftableCount(
                                all, holding(new ItemStack(Items.RAW_IRON)), new Bench(RecipeBookType.FURNACE, 1, 1))
                        == 1,
                "a furnace screen counts what it smelts");
    }

    /** The glass's plate is as wide as the glass; the legend starts after the switch and ends at the plate's edge. */
    private static void theCountStopsAt99AndItsLegendFitsThePlate() {
        Minecraft client = Minecraft.getInstance();
        CatalogueLook look = CatalogueLook.current();
        CatalogueLayout layout = Objects.requireNonNull(
                CatalogueLayout.of(WIDTH, HEIGHT, look, CrtTuning.current(), client.font.lineHeight, true),
                "the default window has the panels");
        Rect plate = layout.shelf().craftable();
        int textX = plate.x()
                + look.casing().switchPad()
                + look.casing().switchWidth()
                + look.casing().switchPad();
        require(CataloguePanel.craftableLegend(99).equals("CRAFTABLE (99)"), "99 is counted out");
        require(CataloguePanel.craftableLegend(100).equals("CRAFTABLE 99+"), "more than 99 reads 99+");
        for (int count : new int[] {0, 9, 99, 100, 250, 1000}) {
            String legend = CataloguePanel.craftableLegend(count);
            int width = client.font.width(CrtText.terminal(legend));
            require(
                    textX + width <= plate.right(),
                    "the legend '" + legend + "' (" + width + " wide from " + textX + ") lies inside its plate "
                            + plate);
        }
    }

    /** A title of three lines moves the card down two lines; every have/need row that is left still ends inside the glass. */
    private static void aTitleOfThreeLinesKeepsEveryNeedRowInTheCard() {
        Minecraft client = Minecraft.getInstance();
        CatalogueLook look = CatalogueLook.current();
        CatalogueLayout layout = Objects.requireNonNull(
                CatalogueLayout.of(WIDTH, HEIGHT, look, CrtTuning.current(), client.font.lineHeight, true),
                "the default window has the panels");
        CatalogueLayout.Card card = layout.card();
        CatalogueLayout.Card lowered = card.lowered(2 * client.font.lineHeight, look.needRow());
        require(
                lowered.needTop() + lowered.needRows() * look.needRow() <= lowered.needBottom(),
                "the last have/need row of the lowered card ends inside " + lowered.needBottom() + ", the card is "
                        + lowered);
        require(lowered.needRows() <= card.needRows(), "lowering the card never makes room for more rows");
        require(
                lowered.gridY() == card.gridY() + 2 * client.font.lineHeight,
                "the grid moves down by the two extra lines");
    }

    private static void togglingCraftableNowKeepsAValidPage() {
        CatalogueState state = new CatalogueState();
        state.turn(4, 10);
        state.toggleCraftableOnly();
        require(state.page(10) == 4, "the switch keeps the page while it is still there, got " + state.page(10));
        require(state.page(2) == 1, "with fewer pages the page shown is the last, not a page past the end");
        require(state.page(1) == 0, "and with one page it is the first");
        require(
                state.page(10) == 4,
                "a list that is briefly short does not make the catalogue forget its page, got " + state.page(10));
        state.toggleCraftableOnly();
        state.turn(-1, 2);
        require(state.page(10) == 0, "a turn moves from the page shown (the last of two), got " + state.page(10));
    }

    private static void longNamesWrapWithoutLosingAWord() {
        ToIntFunction<String> width = text -> text.length() * 6;
        require(
                TextWrap.lines(width, "HULL REPAIR NANOBOTS", 90).equals(List.of("HULL REPAIR", "NANOBOTS")),
                "a name wider than the room breaks at a space, got "
                        + TextWrap.lines(width, "HULL REPAIR NANOBOTS", 90));
        require(TextWrap.lines(width, "IRON INGOT", 90).equals(List.of("IRON INGOT")), "a name that fits is one line");
        String word = "ANTIDISESTABLISHMENTARIANISM";
        List<String> broken = TextWrap.lines(width, word, 60);
        require(String.join("", broken).equals(word), "a word wider than the room is broken, never cut, got " + broken);
        require(broken.stream().allMatch(line -> width.applyAsInt(line) <= 60), "every piece fits, got " + broken);
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
        require(state.page(2) == 1, "the filter keeps the page, which is still there");
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
        return Optional.ofNullable(
                CatalogueLayout.of(width, height, CatalogueLook.current(), CrtTuning.current(), 9, true));
    }

    /**
     * The shortest GUI the consoles draw at on the inventory: level with the image, under a row of its effects as bare
     * icons.
     */
    private static int shortest() {
        return CatalogueLayout.shortest(CatalogueLook.current(), CatalogueLayout.EffectRow.ICONS);
    }

    /** The shortest GUI the consoles draw at on a screen with no effects, the crafting table: level with the image. */
    private static int shortestWithoutEffects() {
        return CatalogueLayout.shortest(CatalogueLook.current(), CatalogueLayout.EffectRow.NONE);
    }

    private static void thePanelsFitAtEveryWindowSize() {
        require(
                layoutAt(WIDTH, HEIGHT).isPresent(),
                "the panels draw at the default window, " + WIDTH + " by " + HEIGHT);
        require(layoutAt(NARROWEST, HEIGHT).isPresent(), "the panels draw at " + NARROWEST + " wide");
        require(layoutAt(NARROWEST - 1, HEIGHT).isEmpty(), "and are hidden, not clipped, one pixel narrower");
        require(layoutAt(320, 240).isEmpty(), "they are hidden at the game's smallest GUI, 320 by 240");
        require(layoutAt(WIDTH, shortest()).isPresent(), "the panels draw in a window just tall enough, " + shortest());
        require(layoutAt(WIDTH, shortest() - 1).isEmpty(), "and are hidden, not clipped, one pixel shorter");
        require(shortest() <= HEIGHT, "the default window is tall enough for the panels");
        require(
                CatalogueLayout.shortest(CatalogueLook.current(), CatalogueLayout.EffectRow.TILES) <= HEIGHT,
                "and for the panels with a row of effect tiles over them");
        CatalogueLook look = CatalogueLook.current();
        require(
                CatalogueLayout.of(WIDTH, shortestWithoutEffects(), look, CrtTuning.current(), 9, false) != null
                        && CatalogueLayout.of(WIDTH, shortestWithoutEffects() - 1, look, CrtTuning.current(), 9, false)
                                == null,
                "a screen with no effects (the crafting table) needs no room for a row of them, only "
                        + shortestWithoutEffects());
        for (int height : new int[] {shortest(), HEIGHT, 270}) {
            for (int width : new int[] {NARROWEST, 426, WIDTH, 480, 854}) {
                requireFits(layoutAt(width, height).orElseThrow(), width, height);
            }
        }
    }

    /** {@code CrtDraw.scanlines} is {@code roundedScanlines} with radius 0, which must start every line at the left edge and end it at the right. */
    private static void aGlassWithNoRadiusKeepsFullWidthScanlines() {
        for (int row = 0; row < HEIGHT; row++) {
            require(
                    PanelLook.scanlineInset(0, row, WIDTH, HEIGHT) == 0,
                    "a scanline of a glass with no radius is cut short at row " + row);
        }
    }

    private static void requireFits(CatalogueLayout layout, int width, int height) {
        CatalogueLook look = CatalogueLook.current();
        String at = " at " + width + " by " + height;
        Rect screen = new Rect(0, 0, width, height);
        Rect inventory = layout.inventory();
        require(inside(inventory, screen), "the inventory image " + inventory + " leaves the screen" + at);
        for (Rect panel : List.of(layout.catalogue(), layout.recipe())) {
            require(inside(panel, screen), "a unit " + panel + " leaves the screen" + at);
            require(!overlap(panel, inventory), "a unit " + panel + " overlaps the inventory " + inventory + at);
            // A phone is a tall slab: a unit twice as tall as it is wide reads as one.
            require(
                    panel.height() * 2 <= panel.width() * 3,
                    "a unit " + panel + " is a tall slab, which reads as a phone" + at);
        }
        require(!overlap(layout.catalogue(), layout.recipe()), "the two units overlap" + at);
        require(layout.recipe().right() <= inventory.x(), "the recipe console is not left of the inventory" + at);
        require(layout.catalogue().x() >= inventory.right(), "the catalogue is not right of the inventory" + at);
        require(layout.catalogue().y() == inventory.y(), "the console stands level with the inventory" + at);
        Rect glass = layout.catalogueGlass();
        CatalogueLayout.Shelf shelf = layout.shelf();
        requireHardware(
                layout.catalogue(),
                glass,
                List.of(shelf.craftable(), shelf.previous(), shelf.next(), shelf.counter()),
                shelf.vents(),
                at);
        CatalogueLayout.Card card = layout.card();
        requireHardware(layout.recipe(), layout.recipeGlass(), List.of(card.spec(), card.fill()), List.of(), at);
        for (Rect rect : List.of(shelf.search(), shelf.favourites())) {
            require(inside(rect, glass), "a part " + rect + " leaves the catalogue's glass " + glass + at);
        }
        Rect grid = new Rect(
                shelf.left(),
                shelf.gridY(),
                (shelf.columns() - 1) * look.gridCell() + look.slotSize(),
                (shelf.rows() - 1) * look.gridCell() + look.slotSize());
        require(inside(grid, glass), "the item grid " + grid + " leaves the glass " + glass + at);
        Rect row = new Rect(
                shelf.left(),
                shelf.favouritesY(),
                (shelf.columns() - 1) * look.gridCell() + look.slotSize(),
                look.slotSize());
        require(inside(row, glass), "the favourites row " + row + " leaves the glass " + glass + at);
        require(!overlap(row, grid), "the favourites row " + row + " overlaps the item grid " + grid + at);
        require(!overlap(grid, shelf.favourites()), "the item grid runs into the favourites" + at);
        require(!overlap(grid, shelf.search()), "the item grid runs into the search field" + at);
        require(
                shelf.columns() == look.gridColumns() && shelf.rows() == look.gridRows(),
                "the grid is " + shelf.columns() + " by " + shelf.rows() + at);
        require(inside(card.spec(), layout.recipe()), "the spec sheet switch leaves the recipe console" + at);
        require(card.needRows() >= 2, "the have/need rows fit, got " + card.needRows() + at);
        int gridBottom = card.gridY() + 3 * look.recipeCell();
        require(gridBottom <= card.needTop(), "the recipe grid runs into the have/need rows" + at);
        require(
                card.needTop() + card.needRows() * look.needRow()
                        <= layout.recipeGlass().bottom(),
                "the have/need rows leave the glass" + at);
        int resultRight = card.left() + look.resultX() + look.slotSize();
        require(resultRight <= card.right(), "the result cell leaves the recipe panel's text area" + at);
    }

    /** A unit's glass lies in its casing, with the controls and the vents on the casing round it, apart from each other and from the glass. */
    private static void requireHardware(Rect casing, Rect glass, List<Rect> controls, List<Rect> vents, String at) {
        require(inside(glass, casing), "the glass " + glass + " leaves its casing " + casing + at);
        List<Rect> parts = new ArrayList<>(controls);
        parts.addAll(vents);
        for (int i = 0; i < parts.size(); i++) {
            Rect part = parts.get(i);
            require(inside(part, casing), "a control or vent " + part + " leaves its casing " + casing + at);
            require(!overlap(part, glass), "a control or vent " + part + " is on the glass " + glass + at);
            for (int j = i + 1; j < parts.size(); j++) {
                require(!overlap(part, parts.get(j)), part + " overlaps " + parts.get(j) + at);
            }
        }
    }

    /** Whether the two share a pixel. */
    private static boolean overlap(Rect a, Rect b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
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
            // Item components are bound only once a world is loaded ("Components not bound yet" before it, #1124).
            context.runOnClient(client -> {
                onlyTheKnownAreListed();
                aTagIngredientListsOnlyObtainableItems(client);
                theCreativeTabsShowOnlyObtainableItems(client);
                theSearchMatchesNamesIdsAndNamespaces();
                craftableNowNeedsTheIngredientsInHand();
                craftableNowMeansCraftableOnThisScreen();
                craftableNowCountsEveryGrid();
                togglingCraftableNowKeepsAValidPage();
                longNamesWrapWithoutLosingAWord();
                theCountStopsAt99AndItsLegendFitsThePlate();
                aTitleOfThreeLinesKeepsEveryNeedRowInTheCard();
                theLookupListsRecipesAndUsesAndStepsThrough();
                pagesStopAtTheEnds();
                favouritesAreKeptPerPlayerAndSurviveReopening();
                aFavouritesFileOfAnotherVersionIsNeverWritten();
                thePanelsFitAtEveryWindowSize();
                aGlassWithNoRadiusKeepsFullWidthScanlines();
            });
            context.runOnClient(client ->
                    CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook()));
            everythingTheRealGameIsAsked(context, singleplayer);
            // Leave the catalogue set to something, so the next world shows whether leaving this one cleared it.
            CatalogueScene.openInventory(context);
            CataloguePanel left = CatalogueScene.panel(context);
            context.runOnClient(client -> {
                left.state().setQuery("left over");
                left.state().toggleCraftableOnly();
                left.state().turn(1, 3);
                left.state().look(left.catalogue(), DRILL, CatalogueState.Mode.RECIPES);
            });
            context.setScreen(() -> null);
            context.runOnClient(client ->
                    CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook()));
        }
        leavingAWorldClearsTheCatalogue(context);
    }

    /** The real disconnect, not {@code resetState()}: a new world opens on an empty catalogue. */
    private static void leavingAWorldClearsTheCatalogue(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Objects.requireNonNull(singleplayer.getServer(), "a second world is running");
            ClientWait.until(
                    context, "the player's inventory", client -> client.player != null && client.level != null);
            CatalogueScene.openInventory(context);
            CatalogueState state = CatalogueScene.panel(context).state();
            require(
                    state.query().isEmpty() && !state.craftableOnly() && state.lookup() == null,
                    "a new world's catalogue starts empty, got '" + state.query() + "', switch " + state.craftableOnly()
                            + ", lookup " + state.lookup());
            require(state.page(3) == 0, "and on the first page");
            context.setScreen(() -> null);
        }
    }

    /** Everything the real game is asked. */
    private static void everythingTheRealGameIsAsked(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        CatalogueScene.openInventory(context);
        // Reopens the inventory, so it comes before the screen and the panel the rest hold are taken.
        theRecipeBookButtonIsGone(context);
        AbstractContainerScreen<?> screen = screen(context);
        CataloguePanel panel = CatalogueScene.panel(context);

        theCatalogueListsOnlyWhatTheBookKnows(context, panel);
        everyStringIsInsideItsGlass(context, screen, "with nothing looked up");
        rAndUShowRecipesAndUsesOfAHoveredItem(context, screen, panel);
        everyStringIsInsideItsGlass(context, screen, "with a recipe shown");
        aRealLeftAndRightClickOpenRecipesAndUses(context, panel, "on the inventory");
        hoveringAnInventorySlotWorksToo(context, singleplayer, screen, panel);
        theRecipePanelCanBePutAway(context, screen, panel);
        theSpecSheetButtonCallsItsHook(context, panel);
        clickingAnItemOpensItsRecipesAndNeverASheet(context, panel);
        theSearchFiltersAndKeepsItsKeys(context, screen, panel);
        theSearchTakesWhatSdlDelivers(context, screen, panel, "on the inventory screen");
        favouritesStarAndPersist(context, screen, panel);
        craftableNowFiltersByWhatIsHeld(context, singleplayer, screen, panel);
        tableRecipesAreCraftableNowWithTheirIconAndLongNamesShowWhole(context, singleplayer, screen, panel);
        theLeadRecipeShowsOnceStringIsHeld(context, singleplayer, panel);
        thePanelsFollowTheWindowSize(context, screen);
        theCatalogueOpensAtAnItem(context);
        openingAnItemFromTheHandbookComesBackToIt(context);
        aQuestBookCardItemOpensItsRecipesOnAClick(context);
        openingAnItemInAWindowTooSmallLeavesTheHandbookOpen(context);
        theCraftingTableHasTheCatalogueToo(context);
        CatalogueScene.openInventory(context);
        theCatalogueRemembersItsState(context, CatalogueScene.panel(context));
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
        // A hovered item's name is drawn and recorded too, and sits in no glass: nothing is hovered while the text
        // is read.
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
            // A string is in a glass or on the plate of a control, which carries its own legend.
            List<Rect> plates = new ArrayList<>(List.of(
                    layout.shelf().craftable(),
                    layout.shelf().previous(),
                    layout.shelf().next(),
                    layout.shelf().counter()));
            plates.add(layout.card().spec());
            plates.add(layout.card().fill());
            plates.add(layout.card().back());
            for (Drawn text : drawn) {
                Rect box = new Rect(
                        text.box().x(),
                        text.box().y(),
                        text.box().width(),
                        text.box().height());
                require(
                        inside(box, catalogue)
                                || inside(box, recipe)
                                || inside(box, inventory)
                                || plates.stream().anyMatch(plate -> inside(box, plate)),
                        "text '" + text.shortText() + "' at " + box
                                + " is in no glass, on no plate and not in the inventory, " + when
                                + " (catalogue glass " + catalogue + ", recipe glass " + recipe + ")");
            }
            require(
                    texts.stream().anyMatch(text -> text.matches("\\d+/\\d+")),
                    "the catalogue draws its page counter " + when + ", it draws " + texts);
            String craftable = craftableLabel(panel);
            for (String expected : List.of("PARTS CATALOGUE", "FAVOURITES", craftable)) {
                require(
                        texts.contains(expected),
                        "the catalogue draws '" + expected + "' " + when + ", it draws " + texts);
            }
            require(texts.contains("SEARCH") == hintShown, "the search field shows its hint only while empty " + when);
            CatalogueLayout.Shelf shelf = layout.shelf();
            require(
                    labelInside(drawn, craftable, shelf.craftable()),
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
                require(
                        labelInside(drawn, "<", layout.card().back()),
                        "the back knob's arrow lies inside the knob " + when);
                KnownRecipe shownRecipe = panel.state().shownRecipe(panel.catalogue());
                if (shownRecipe != null) {
                    require(
                            labelInside(drawn, panel.fillLegend(), layout.card().fill()),
                            "the fill switch's legend '" + panel.fillLegend() + "' lies inside its plate " + when);
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

    /**
     * The glass has rounded corners (#984): no drawn string and no item icon of the two consoles may reach the region outside the arcs or the
     * dark ring the glass's bulge puts round its edge ({@code vignetteWidth}), and no cell outline may reach the region outside the arcs. The
     * window is {@code width} by {@code height}, the smallest the layout draws at or the default.
     */
    private static void nothingIsCutByTheGlassCorners(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, int width, int height) {
        CatalogueScene.parkCursor(
                context, CatalogueScene.layout(context, context.computeOnClient(client -> panelOf(screen))));
        context.runOnClient(client -> {
            CatalogueLayout layout = Objects.requireNonNull(panelOf(screen).layout(), "the panels are laid out");
            String at = " at " + width + " by " + height;
            CatalogueLook look = CatalogueLook.current();
            int ring = look.casing().vignetteWidth();
            List<Rect> glasses = List.of(layout.catalogueGlass(), layout.recipeGlass());
            for (Drawn text : drawn(client, screen)) {
                Rect box = new Rect(
                        text.box().x(),
                        text.box().y(),
                        text.box().width(),
                        text.box().height());
                for (Rect glass : glasses) {
                    if (inside(box, glass)) {
                        requireClearOfCorners(box, glass, ring, "text '" + text.shortText() + "'" + at);
                    }
                }
            }
            CatalogueLayout.Shelf shelf = layout.shelf();
            List<Rect> shelfCells = new ArrayList<>();
            for (int row = 0; row < shelf.rows(); row++) {
                for (int column = 0; column < shelf.columns(); column++) {
                    shelfCells.add(new Rect(
                            shelf.left() + column * look.gridCell(),
                            shelf.gridY() + row * look.gridCell(),
                            look.slotSize(),
                            look.slotSize()));
                }
            }
            for (int column = 0; column < shelf.columns(); column++) {
                shelfCells.add(new Rect(
                        shelf.left() + column * look.gridCell(),
                        shelf.favouritesY(),
                        look.slotSize(),
                        look.slotSize()));
            }
            List<Rect> recipeCells = new ArrayList<>();
            CatalogueLayout.Card card = layout.card();
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    recipeCells.add(new Rect(
                            card.left() + column * look.recipeCell(),
                            card.gridY() + row * look.recipeCell(),
                            look.slotSize(),
                            look.slotSize()));
                }
            }
            recipeCells.add(new Rect(
                    card.left() + look.resultX(), card.gridY() + look.resultY(), look.slotSize(), look.slotSize()));
            for (Rect cell : shelfCells) {
                requireClearOfCorners(cell, layout.catalogueGlass(), 0, "the cell outline " + cell + at);
                requireClearOfCorners(icon(cell, look), layout.catalogueGlass(), ring, "the item icon in " + cell + at);
            }
            for (Rect cell : recipeCells) {
                requireClearOfCorners(cell, layout.recipeGlass(), 0, "the recipe cell outline " + cell + at);
                requireClearOfCorners(
                        icon(cell, look), layout.recipeGlass(), ring, "the recipe item icon in " + cell + at);
            }
            for (int row = 0; row < card.needRows(); row++) {
                Rect need = new Rect(card.left(), card.needTop() + row * look.needRow(), 16, 16);
                requireClearOfCorners(need, layout.recipeGlass(), ring, "the have/need icon " + need + at);
            }
        });
    }

    /** The legend of the switch: its words and how many items are craftable now. */
    private static String craftableLabel(CataloguePanel panel) {
        return CataloguePanel.craftableLegend(panel.craftableCount());
    }

    /** The 16 by 16 item a catalogue cell draws inside its outline. */
    private static Rect icon(Rect cell, CatalogueLook look) {
        return new Rect(cell.x() + look.iconInset(), cell.y() + look.iconInset(), 16, 16);
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

    private static int nameLength(Item item) {
        return item.getDefaultInstance().getHoverName().getString().length();
    }

    /** With an item hovered, the strip that names it lies in the catalogue's glass and names it whole, on as many lines as it takes. */
    private static void theNameStripIsInsideTheCatalogueGlass(
            ClientGameTestContext context,
            AbstractContainerScreen<?> screen,
            CataloguePanel panel,
            CatalogueLayout layout) {
        Item hovered = Objects.requireNonNull(
                context.computeOnClient(client -> panel.hoveredItem()), "an item of the catalogue is hovered");
        String name = hovered.getDefaultInstance().getHoverName().getString().toUpperCase(Locale.ROOT);
        List<String> lines = context.computeOnClient(client -> panel.nameLines(hovered));
        require(
                String.join(" ", lines).equals(name),
                "the strip names " + name + " whole, in " + lines + ", with nothing cut");
        List<Drawn> drawn = context.computeOnClient(client -> drawn(client, screen));
        for (String line : lines) {
            Drawn strip = drawn.stream()
                    .filter(text -> text.text().equals(line))
                    .findFirst()
                    .orElseThrow(
                            () -> new AssertionError("the hovered " + name + " draws '" + line + "', drew " + drawn));
            require(
                    inside(
                            new Rect(
                                    strip.box().x(),
                                    strip.box().y(),
                                    strip.box().width(),
                                    strip.box().height()),
                            layout.catalogueGlass()),
                    "the name strip line '" + line + "' of " + name + " lies inside the catalogue glass "
                            + layout.catalogueGlass() + ", at " + strip.box());
        }
        require(
                drawn.stream().noneMatch(text -> text.text().contains("..")),
                "no text is cut with dots while " + name + " is hovered, drew " + drawn);
    }

    private static void rAndUShowRecipesAndUsesOfAHoveredItem(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, CataloguePanel panel) {
        // The server may have taught more recipes of these items meanwhile: the counters below count the
        // fixtures alone.
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
                context,
                CatalogueScene.gridCell(
                        layout,
                        Math.min(
                                        layout.shelf().columns(),
                                        context.computeOnClient(
                                                client -> panel.shownItems().size()))
                                - 1));
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
        // The strip belongs to the glass of its panel: the rightmost column, and the longest name on the page.
        int columns = layout.shelf().columns();
        // The fixtures list six items, fewer than the grid's columns.
        int rightmost = Math.min(
                        columns,
                        context.computeOnClient(client -> panel.shownItems().size()))
                - 1;
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, rightmost));
        theNameStripIsInsideTheCatalogueGlass(context, screen, panel, layout);
        List<Item> page = context.computeOnClient(client -> panel.shownItems());
        Item longest = page.subList(0, Math.min(page.size(), layout.shelf().perPage())).stream()
                .max(Comparator.comparingInt(PartsCatalogueClientTest::nameLength))
                .orElseThrow();
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, page.indexOf(longest)));
        theNameStripIsInsideTheCatalogueGlass(context, screen, panel, layout);
        for (int row = 0;
                (row + 1) * columns <= Math.min(page.size(), layout.shelf().perPage());
                row++) {
            CatalogueScene.hover(context, CatalogueScene.gridCell(layout, row * columns + columns - 1));
            theNameStripIsInsideTheCatalogueGlass(context, screen, panel, layout);
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
        Rect firstCell = new Rect(card.left(), card.gridY(), 18, 18);
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
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> lookedAt(panel.state()).item()) == HangarParts.ROTOR_HUB,
                "a left click on a cell shows its recipes");
        CatalogueScene.hover(
                context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, Items.COPPER_INGOT)));
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
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

    private static void aRealLeftAndRightClickOpenRecipesAndUses(
            ClientGameTestContext context, CataloguePanel panel, String where) {
        // Start on the other mode, so a click that does nothing cannot pass for the one asked.
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.USES));
        context.waitTicks(1);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, DRILL)));
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        CatalogueState.Lookup left = context.computeOnClient(client -> lookedAt(panel.state()));
        require(
                left.item() == DRILL && left.mode() == CatalogueState.Mode.RECIPES,
                "a real left click " + where + " opens the recipes of the item, got " + left);
        // Another item, one on the first page (the crafting table lists copper past it): the right click is not the
        // left's cell.
        Item other = context.computeOnClient(client -> panel.shownItems().stream()
                .filter(item -> item != DRILL)
                .findFirst()
                .orElseThrow());
        CatalogueScene.hover(
                context,
                CatalogueScene.gridCell(
                        CatalogueScene.layout(context, panel), CatalogueScene.indexOf(context, panel, other)));
        RealInput.click(context, InputConstants.MOUSE_BUTTON_RIGHT);
        CatalogueState.Lookup right = context.computeOnClient(client -> lookedAt(panel.state()));
        require(
                right.item() == other && right.mode() == CatalogueState.Mode.USES,
                "a real right click " + where + " opens the uses of the item, got " + right);
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
            context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            context.waitTicks(1);
            require(opened.equals(List.of(DRILL)), "SPEC SHEET calls its hook with the item looked up, got " + opened);
        } finally {
            context.runOnClient(client -> SpecSheetHook.set(SpecSheetOpener::show));
        }
    }

    /**
     * A real left click on an item opens its recipes, wherever the item is (a catalogue cell, a favourite, an ingredient or the result in
     * the recipe card), and never a spec sheet: the sheet comes only from the SPEC SHEET button and the server's first-craft pop-up.
     */
    private static void clickingAnItemOpensItsRecipesAndNeverASheet(
            ClientGameTestContext context, CataloguePanel panel) {
        UUID player =
                context.computeOnClient(client -> ClientSession.player(client).getUUID());
        for (Item left : CatalogueFavourites.shared().items(player)) {
            CatalogueFavourites.shared().toggle(player, left);
        }
        CatalogueFavourites.shared().toggle(player, Items.IRON_INGOT);
        try {
            context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.USES));
            context.waitTicks(2);
            CatalogueLayout layout = CatalogueScene.layout(context, panel);
            CatalogueLook look = CatalogueLook.current();
            CatalogueLayout.Shelf shelf = layout.shelf();
            // A cell of the grid, a favourite, and the first ingredient and the result of the drill head's 3 x
            // 3 recipe.
            Item other = context.computeOnClient(client -> panel.shownItems().stream()
                    .filter(item -> item != DRILL && item != Items.IRON_INGOT)
                    .findFirst()
                    .orElseThrow());
            Rect cell = CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, other));
            Rect favourite = new Rect(shelf.left(), shelf.favouritesY(), look.slotSize(), look.slotSize());
            clickAndExpectRecipes(context, panel, cell, other, "a catalogue cell");
            context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.USES));
            context.waitTicks(2);
            clickAndExpectRecipes(context, panel, favourite, Items.IRON_INGOT, "a favourite");
            // The server teaches its own recipe of the drill head, which sorts before the fixtures: read the recipe the
            // card shows.
            context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
            context.waitTicks(2);
            KnownRecipe shown = Objects.requireNonNull(
                    context.computeOnClient(client -> panel.state().shownRecipe(panel.catalogue())),
                    "the drill head has a recipe");
            int at = 0;
            while (shown.cells().get(at).size() != 1) {
                at++;
            }
            Item ingredientItem = shown.cells().get(at).getFirst().getItem();
            CatalogueLayout.Card card = cardShown(context, panel);
            clickAndExpectRecipes(
                    context,
                    panel,
                    new Rect(
                            card.left() + (at % shown.columns()) * look.recipeCell(),
                            card.gridY() + (at / shown.columns()) * look.recipeCell(),
                            look.slotSize(),
                            look.slotSize()),
                    ingredientItem,
                    "an ingredient in the recipe card");
            // Iron's uses: the result of the recipe shown is not iron, so a click that did nothing could not pass.
            context.runOnClient(
                    client -> panel.state().look(panel.catalogue(), Items.IRON_INGOT, CatalogueState.Mode.USES));
            context.waitTicks(2);
            Item made = context.computeOnClient(
                    client -> Objects.requireNonNull(panel.state().shownRecipe(panel.catalogue()), "a recipe uses iron")
                            .results()
                            .getFirst()
                            .getItem());
            require(made != Items.IRON_INGOT, "setup: the recipe shown makes something else than iron");
            CatalogueLayout.Card madeCard = cardShown(context, panel);
            clickAndExpectRecipes(
                    context,
                    panel,
                    new Rect(
                            madeCard.left() + look.resultX(),
                            madeCard.gridY() + look.resultY(),
                            look.slotSize(),
                            look.slotSize()),
                    made,
                    "the result in the recipe card");
        } finally {
            CatalogueFavourites.shared().toggle(player, Items.IRON_INGOT);
            context.runOnClient(client -> panel.state().clear());
        }
    }

    /** The recipe console's content as drawn: moved down by each line the title takes beyond the first. */
    private static CatalogueLayout.Card cardShown(ClientGameTestContext context, CataloguePanel panel) {
        int titleLines = context.computeOnClient(client -> panel.titleLines().size());
        return CatalogueScene.layout(context, panel)
                .card()
                .lowered(
                        (titleLines - 1) * context.computeOnClient(client -> client.font.lineHeight),
                        CatalogueLook.current().needRow());
    }

    private static void clickAndExpectRecipes(
            ClientGameTestContext context, CataloguePanel panel, Rect cell, Item item, String where) {
        CatalogueScene.hover(context, cell);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        CatalogueState.Lookup now = context.computeOnClient(client -> lookedAt(panel.state()));
        require(
                now.item() == item && now.mode() == CatalogueState.Mode.RECIPES,
                "a click on " + where + " opens the recipes of " + item + ", got " + now);
        require(
                context.computeOnClient(client -> panel.sheetItem()) == null
                        && context.computeOnClient(client -> client.gui.screen() instanceof InventoryScreen),
                "a click on " + where + " opens no spec sheet");
    }

    /** The handbook's index card of a quest step lists the items it needs; a click on one opens that item's recipes in the catalogue. */
    private static void aQuestBookCardItemOpensItsRecipesOnAClick(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.waitTicks(2);
        Identifier directive = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "handbook/test/card_item");
        Identifier chapterId = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "card_item_chapter");
        HandbookChapter chapter = new HandbookChapter(
                1,
                Component.literal("Chapter 1"),
                List.of(new HandbookChapter.Entry(
                        directive,
                        Component.literal("Make a drill head."),
                        List.of(new HandbookChapter.Target(BuiltInRegistries.ITEM.getKey(DRILL), 1)))));
        List<HandbookPage> pages = HandbookPages.of(Map.of(chapterId, chapter), Set.of());
        HandbookScreen[] opened = new HandbookScreen[1];
        context.setScreen(() -> {
            opened[0] = new HandbookScreen(pages, id -> false, id -> {}, List.of());
            return opened[0];
        });
        context.runOnClient(client -> {
            HandbookScreen screen = opened[0];
            int page = 0;
            while (!(pages.get(page) instanceof HandbookPage.Chapter)) {
                page++;
            }
            screen.goTo(page);
            for (int tick = 0; tick < HandbookScreenTuning.current().flipTicks(); tick++) {
                screen.tick();
            }
        });
        Rect box = Objects.requireNonNull(
                context.computeOnClient(client -> opened[0].indexCardItemBox(1, DRILL)),
                "the chapter's index card lists the drill head it needs");
        CatalogueScene.hover(context, box);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        ClientWait.screen(context, InventoryScreen.class);
        context.waitTicks(3);
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueState.Lookup lookup = context.computeOnClient(client -> lookedAt(panel.state()));
        require(
                lookup.item() == DRILL && lookup.mode() == CatalogueState.Mode.RECIPES,
                "a click on a quest-book index card's item opens its recipes, got " + lookup);
        require(context.computeOnClient(client -> panel.sheetItem()) == null, "and no spec sheet");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(1);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the handbook back", client -> client.gui.screen() == opened[0]);
        context.setScreen(() -> null);
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
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
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

    /** The handbook opened from the pause menu: its quest map's item click puts the inventory over it, and closing that comes back. */
    private static void openingAnItemFromTheHandbookComesBackToIt(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.waitTicks(2);
        Screen parent = context.computeOnClient(client -> {
            Screen pause = new PauseScreen(true);
            HandbookScreen.open(client, pause);
            return pause;
        });
        Screen handbook = context.computeOnClient(client -> client.gui.screen());
        require(handbook instanceof HandbookScreen, "setup: the handbook is open, got " + handbook);
        context.runOnClient(client -> CatalogueScreens.open(DRILL));
        ClientWait.screen(context, InventoryScreen.class);
        context.waitTicks(3);
        // The first Escape puts the recipe panel away, the second closes the inventory.
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(1);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the handbook back", client -> client.gui.screen() == handbook);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the handbook's parent", client -> client.gui.screen() == parent);
        context.setScreen(() -> null);
    }

    private static void openingAnItemInAWindowTooSmallLeavesTheHandbookOpen(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.waitTicks(2);
        context.runOnClient(client -> HandbookScreen.open(client, null));
        Screen handbook = context.computeOnClient(client -> client.gui.screen());
        require(handbook instanceof HandbookScreen, "setup: the handbook is open, got " + handbook);
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        try {
            // The screen is not resized, so only the catalogue's own check of the window sees it small.
            context.runOnClient(client -> client.getWindow().setGuiScale(4));
            require(
                    context.computeOnClient(client -> CatalogueLayout.of(
                                    client.getWindow().getGuiScaledWidth(),
                                    client.getWindow().getGuiScaledHeight(),
                                    CatalogueLook.current(),
                                    CrtTuning.current(),
                                    client.font.lineHeight,
                                    true)
                            == null),
                    "setup: the window is too small for the consoles at GUI scale 4");
            context.runOnClient(client -> CatalogueScreens.open(DRILL));
            context.waitTicks(3);
            require(
                    context.computeOnClient(client -> client.gui.screen()) == handbook,
                    "a click on an item in a window too small for the consoles leaves the handbook open");
        } finally {
            context.runOnClient(client -> client.getWindow().setGuiScale(scale));
            context.setScreen(() -> null);
        }
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
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(1);
        require(context.computeOnClient(client -> panel.searchFocused()), "a click on the search field focuses it");
        RealInput.type(context, "rotor");
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
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        RealInput.type(context, "a very long search that cannot fit the field");
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
        // The runner's holdControl does not reach the key event's modifiers, so the press goes in with the edit
        // shortcut key (Ctrl, or Cmd on a Mac) set.
        RealInput.key(context, InputConstants.KEY_BACKSPACE, InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER);
        require(context.computeOnClient(client -> panel.state().query()).isEmpty(), "ctrl+backspace clears the search");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(1);
    }

    /** The field under SDL's rules: the other typing assertions inject {@code textInput} themselves, so only these prove #1269. */
    private static void theSearchTakesWhatSdlDelivers(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, CataloguePanel panel, String where) {
        UUID player =
                context.computeOnClient(client -> ClientSession.player(client).getUUID());
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, layout.shelf().search());
        require(!sdlTextInputOn(context), "SDL text input is off before the field is clicked " + where);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(context.computeOnClient(client -> panel.searchFocused()), "a click focuses the field " + where);
        require(sdlTextInputOn(context), "the focused field turns SDL text input on, or no text arrives " + where);
        for (char letter : "rotor".toCharArray()) {
            sdlKeystroke(context, Character.toUpperCase(letter), String.valueOf(letter));
        }
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> panel.state().query()).equals("rotor"),
                "keys and text as SDL delivers them fill the field " + where);
        sdlKeystroke(context, InputConstants.KEY_LEFT, null);
        sdlKeystroke(context, InputConstants.KEY_RIGHT, null);
        sdlKeystroke(context, InputConstants.KEY_BACKSPACE, null);
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> panel.state().query()).equals("roto"),
                "backspace works after the arrow keys " + where);
        require(context.computeOnClient(client -> panel.searchFocused()), "the arrow keys keep the focus " + where);
        sdlKeystroke(context, InputConstants.KEY_ESCAPE, null);
        context.waitTicks(1);
        require(!context.computeOnClient(client -> panel.searchFocused()), "escape unfocuses the field " + where);
        require(!sdlTextInputOn(context), "an unfocused field gives SDL text input back " + where);
        require(context.computeOnClient(client -> client.gui.screen()) == screen, "escape keeps the screen " + where);
        context.runOnClient(client -> panel.state().setQuery(""));
        context.waitTicks(1);

        // The catalogue's own keys stay out of a focused field: over an item, R, U, A and E would each act.
        CatalogueState.Lookup lookup =
                context.computeOnClient(client -> panel.state().lookup());
        Set<Item> starred = Set.copyOf(CatalogueFavourites.shared().items(player));
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, DRILL)));
        require(context.computeOnClient(client -> panel.hoveredItem()) == DRILL, "the cursor is over an item " + where);
        sdlKeystroke(context, InputConstants.KEY_F, null, true);
        require(context.computeOnClient(client -> panel.searchFocused()), "Ctrl+F focuses the field " + where);
        // The text a key types clears a lookup, so R and U are checked as bare presses, before the typing.
        sdlKeystroke(context, InputConstants.KEY_R, null);
        sdlKeystroke(context, InputConstants.KEY_U, null);
        require(
                context.computeOnClient(client -> panel.state().lookup()) == lookup,
                "R and U over an item look nothing up while the field is focused " + where);
        for (char letter : "aruE".toCharArray()) {
            sdlKeystroke(context, Character.toUpperCase(letter), String.valueOf(letter));
        }
        context.waitTicks(1);
        require(
                context.computeOnClient(client -> panel.state().query()).equals("aruE"),
                "A, R, U and E are typed into the field " + where);
        require(
                Set.copyOf(CatalogueFavourites.shared().items(player)).equals(starred),
                "A over an item stars nothing while the field is focused " + where);
        require(
                context.computeOnClient(client -> client.gui.screen()) == screen,
                "E in the field does not close the screen " + where);
        sdlKeystroke(context, InputConstants.KEY_ESCAPE, null);
        context.runOnClient(client -> panel.state().setQuery(""));
        context.waitTicks(1);
    }

    private static boolean sdlTextInputOn(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> SDLKeyboard.SDL_TextInputActive(client.getWindow().handle()));
    }

    private static void sdlKeystroke(ClientGameTestContext context, int key, @Nullable String text) {
        sdlKeystroke(context, key, text, false);
    }

    /** One key as SDL delivers it: the press, the text it types (none for a control key) and the release. */
    private static void sdlKeystroke(ClientGameTestContext context, int key, @Nullable String text, boolean shortcut) {
        context.runOnClient(client -> {
            long window = client.getWindow().handle();
            // The runner's holdControl does not reach the key event's modifiers, so the shortcut key is set on it.
            KeyEvent event = new KeyEvent(
                    key,
                    SDLKeyboard.SDL_GetKeyFromScancode(key, (short) 0, false),
                    shortcut ? InputQuirks.EDIT_SHORTCUT_KEY_MODIFIER : 0);
            client.keyboardHandler.keyPress(window, InputConstants.PRESS, event);
            if (text != null) {
                client.keyboardHandler.textInput(window, text);
            }
            client.keyboardHandler.keyPress(window, InputConstants.RELEASE, event);
        });
    }

    private static void favouritesStarAndPersist(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, CataloguePanel panel) {
        UUID player =
                context.computeOnClient(client -> ClientSession.player(client).getUUID());
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        // A star left in the shared file by an aborted run is not this test's: take every one off through the real API.
        for (Item left : CatalogueFavourites.shared().items(player)) {
            CatalogueFavourites.shared().toggle(player, left);
        }
        context.waitTicks(1);
        try {
            CatalogueScene.hover(
                    context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, DRILL)));
            context.getInput().pressKey(InputConstants.KEY_A);
            context.waitTicks(1);
            require(
                    CatalogueFavourites.shared().items(player).contains(DRILL),
                    "A on a hovered cell stars its item for this player");
            Rect first = new Rect(layout.shelf().left(), layout.shelf().favouritesY(), 18, 18);
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
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(1);
        List<Item> shown = context.computeOnClient(client -> panel.shownItems());
        require(
                context.computeOnClient(client -> panel.state().craftableOnly()),
                "a click on the toggle turns the filter on");
        require(shown.contains(HangarParts.ROTOR_HUB), "an iron and two copper make the rotor hub, got " + shown);
        require(!shown.contains(DRILL), "but not the drill head, which needs more iron, got " + shown);
        everyStringIsInsideItsGlass(context, screen, "with the craftable filter on");
        // The text check parked the cursor off the button.
        CatalogueScene.hover(context, layout.shelf().craftable());
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(1);
        require(!context.computeOnClient(client -> panel.state().craftableOnly()), "a second click turns it off");
    }

    /**
     * #1602: the real lead recipe, the one the tow cable needs, reaches the catalogue from the server's book once
     * string is held.
     */
    private static void theLeadRecipeShowsOnceStringIsHeld(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, CataloguePanel panel) {
        singleplayer.getServer().runCommand("clear @p");
        singleplayer.getServer().runCommand("give @p minecraft:string 5");
        ClientWait.until(
                context,
                "the lead recipe in the catalogue",
                client -> !panel.catalogue().recipesFor(Items.LEAD).isEmpty());
        KnownRecipe lead = context.computeOnClient(
                client -> panel.catalogue().recipesFor(Items.LEAD).getFirst());
        require(
                lead.ingredientItems().equals(Set.of(Items.STRING)),
                "the lead is made of string alone, got " + lead.ingredientItems());
    }

    /** A 3 x 3 recipe is craftable now at the 2 x 2 inventory, marked with the table; its long name is shown whole in the strip and the title. */
    private static void tableRecipesAreCraftableNowWithTheirIconAndLongNamesShowWhole(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            AbstractContainerScreen<?> screen,
            CataloguePanel panel) {
        Item nanobots = RepairRegistry.item(Consumable.HULL_NANOBOTS);
        context.runOnClient(client -> {
            ClientRecipeBook book = ClientSession.player(client).getRecipeBook();
            book.add(CatalogueFixtures.nanobots());
            book.rebuildCollections();
        });
        singleplayer.getServer().runCommand("clear @p");
        singleplayer.getServer().runCommand("give @p minecraft:iron_ingot 8");
        singleplayer.getServer().runCommand("give @p minecraft:redstone 1");
        ClientWait.until(
                context,
                "eight iron and a redstone in the inventory, and the nanobots recipe in the catalogue",
                client -> ClientSession.player(client).getInventory().contains(new ItemStack(Items.IRON_INGOT, 8))
                        && ClientSession.player(client).getInventory().contains(new ItemStack(Items.REDSTONE))
                        && !panel.catalogue().recipesFor(nanobots).isEmpty());
        context.waitTicks(2);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, layout.shelf().craftable());
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        List<Item> shown = context.computeOnClient(client -> panel.shownItems());
        require(
                shown.contains(nanobots) && shown.contains(DRILL) && !shown.contains(HangarParts.ROTOR_HUB),
                "eight iron and a redstone make the nanobots (3 x 3 only) and the drill head, got " + shown);
        // The server teaches the vanilla recipes the iron opens too, so the list is longer than the two fixtures.
        require(
                context.computeOnClient(client -> panel.craftableCount()) == shown.size(),
                "the switch counts what the filtered list shows, " + shown.size() + ", got "
                        + context.computeOnClient(client -> panel.craftableCount()));
        require(
                context.computeOnClient(client -> panel.marksTable(nanobots)),
                "the nanobots, made only at the table, carry the table icon at the inventory");
        require(
                context.computeOnClient(client -> !panel.marksTable(DRILL)),
                "the drill head the 2 x 2 grid makes carries none");
        everyStringIsInsideItsGlass(context, screen, "with a table recipe craftable now");

        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, shown.indexOf(nanobots)));
        List<String> lines = context.computeOnClient(client -> panel.nameLines(nanobots));
        require(lines.size() >= 2, "the nanobots' name does not fit one line of the strip, got " + lines);
        theNameStripIsInsideTheCatalogueGlass(context, screen, panel, layout);
        context.getInput().pressKey(InputConstants.KEY_R);
        ClientWait.until(context, "the recipe console", client -> panel.showsRecipePanel());
        everyStringIsInsideItsGlass(context, screen, "with a long title");
        List<String> texts = context.computeOnClient(client -> texts(client, screen));
        for (String line : lines) {
            require(texts.contains(line), "the recipe title shows '" + line + "' whole, it draws " + texts);
        }
        context.runOnClient(client -> {
            List<String> title = panel.titleLines();
            CatalogueLayout.Card moved = layout.card()
                    .lowered(
                            (title.size() - 1) * client.font.lineHeight,
                            CatalogueLook.current().needRow());
            require(
                    moved.needTop() + moved.needRows() * CatalogueLook.current().needRow() <= moved.needBottom(),
                    "with a title of " + title.size() + " lines every need row of the 3 x 3 recipe stays in the card");
        });
        require(texts.stream().noneMatch(text -> text.contains("..")), "no title is cut with dots, it draws " + texts);

        context.runOnClient(client -> {
            panel.state().clear();
            panel.state().toggleCraftableOnly();
            CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook());
        });
        singleplayer.getServer().runCommand("clear @p");
        context.waitTicks(2);
    }

    /** The search, the switch, the page and the lookup are the client's, not one screen's: they outlive the screen and are the next one's. */
    private static void theCatalogueRemembersItsState(ClientGameTestContext context, CataloguePanel panel) {
        CatalogueScene.hover(
                context, CatalogueScene.layout(context, panel).shelf().search());
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(
                context.computeOnClient(client -> panel.searchFocused()),
                "the field is focused before the screen closes");
        context.runOnClient(client -> {
            CatalogueState state = panel.state();
            state.setQuery("o");
            if (!state.craftableOnly()) {
                state.toggleCraftableOnly();
            }
            state.turn(1, 3);
            state.look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES);
            client.gui.setScreen(null);
            client.gui.setScreen(new InventoryScreen(ClientSession.player(client)));
            CataloguePanel again = panelOf(shown(client));
            requireRemembered(again, "the reopened inventory");
            require(again != panel, "the reopened inventory has a panel of its own");
            require(!again.searchFocused(), "a new panel starts with the search unfocused");
            require(
                    !SDLKeyboard.SDL_TextInputActive(client.getWindow().handle()),
                    "and with SDL text input off, so the first click is what starts it");
            client.gui.setScreen(null);
            client.gui.setScreen(craftingScreen(client));
            requireRemembered(panelOf(shown(client)), "the crafting table");
            // What leaving a world does: the next panel is as a new client's.
            CatalogueScreens.resetState();
            client.gui.setScreen(null);
            client.gui.setScreen(new InventoryScreen(ClientSession.player(client)));
            CatalogueState fresh = panelOf(shown(client)).state();
            require(fresh.query().isEmpty(), "after a reset the search is empty, got '" + fresh.query() + "'");
            require(!fresh.craftableOnly(), "after a reset the switch is off");
            require(fresh.lookup() == null, "after a reset nothing is looked up");
            require(fresh.page(3) == 0, "after a reset the catalogue is on its first page");
            client.gui.setScreen(null);
        });
        CatalogueScene.openInventory(context);
        context.waitTicks(1);
    }

    private static void requireRemembered(CataloguePanel panel, String where) {
        CatalogueState state = panel.state();
        require(state.query().equals("o"), where + " keeps the search, got '" + state.query() + "'");
        require(state.craftableOnly(), where + " keeps the craftable switch");
        require(state.page(3) == 1, where + " keeps the page, got " + state.page(3));
        require(
                lookedAt(state).item() == DRILL && lookedAt(state).mode() == CatalogueState.Mode.RECIPES,
                where + " keeps the open recipe");
    }

    private static CraftingScreen craftingScreen(Minecraft client) {
        return new CraftingScreen(
                new CraftingMenu(0, ClientSession.player(client).getInventory()),
                ClientSession.player(client).getInventory(),
                Component.translatable("container.crafting"));
    }

    private static void thePanelsFollowTheWindowSize(ClientGameTestContext context, AbstractContainerScreen<?> screen) {
        CataloguePanel panel = context.computeOnClient(client -> panelOf(screen));
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
        for (int width : new int[] {NARROWEST, WIDTH}) {
            context.runOnClient(client -> screen.resize(width, HEIGHT));
            context.waitTicks(2);
            everyStringIsInsideItsGlass(context, screen, "at " + width + " by " + HEIGHT);
            nothingIsCutByTheGlassCorners(context, screen, width, HEIGHT);
            require(
                    context.computeOnClient(client -> leftPos(screen)) == (width - CatalogueLayout.IMAGE_WIDTH) / 2,
                    "the inventory image is centred at " + width);
        }
        context.runOnClient(client -> screen.resize(NARROWEST, shortest()));
        context.waitTicks(2);
        nothingIsCutByTheGlassCorners(context, screen, NARROWEST, shortest());
        sdlKeystroke(context, InputConstants.KEY_F, null, true);
        require(sdlTextInputOn(context), "the field has SDL text input before the window shrinks");
        for (int width : new int[] {NARROWEST - 1, 320}) {
            context.runOnClient(client -> screen.resize(width, HEIGHT));
            context.waitTicks(2);
            require(context.computeOnClient(client -> panel.layout() == null), "the panels are hidden at " + width);
            require(
                    !context.computeOnClient(client -> panel.searchFocused()) && !sdlTextInputOn(context),
                    "a hidden catalogue holds no focus and no SDL text input at " + width);
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
        context.runOnClient(client -> client.gui.setScreen(craftingScreen(client)));
        ClientWait.screen(context, CraftingScreen.class);
        context.waitTicks(3);
        AbstractContainerScreen<?> table = screen(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
        context.waitTicks(1);
        require(context.computeOnClient(client -> panel.layout() != null), "the crafting table has the panels");
        aRealLeftAndRightClickOpenRecipesAndUses(context, panel, "on the crafting table");
        everyStringIsInsideItsGlass(context, table, "on the crafting table");
        theSearchTakesWhatSdlDelivers(context, table, panel, "on the crafting table");
        require(
                context.computeOnClient(client -> leftPos(table)) == (WIDTH - CatalogueLayout.IMAGE_WIDTH) / 2,
                "the crafting table stands where the layout assumes");
    }
}
