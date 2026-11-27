package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;
import static io.github.pkeppeler.deepcharter.test.support.GlassCorners.requireClearOfCorners;

import com.google.common.base.Splitter;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.StepToast;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetOpener;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.SpecSheetScreen;
import io.github.pkeppeler.deepcharter.client.handbook.mixin.ToastInstanceAccess;
import io.github.pkeppeler.deepcharter.client.handbook.mixin.ToastManagerAccess;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.handbook.SpecSheet;
import io.github.pkeppeler.deepcharter.handbook.SpecSheetPopups;
import io.github.pkeppeler.deepcharter.handbook.SpecSheets;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientCursor;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client GameTest for #848, the spec sheet. In a loaded world, with no screen of the catalogue open: the sheet of every Company item and of stock items lies whole inside
 * its glass at the default window and at the game's smallest GUI, and its ratings are the game's own numbers. Then: the SPEC
 * SHEET button opens the sheet over the catalogue, and a sheet pops up when the server says so, over the catalogue, over no screen, and
 * after another screen has closed.
 */
public class SpecSheetClientTest implements FabricClientGameTest {
    /** The default window, 854 by 480 at GUI scale 2. */
    private static final int WIDTH = 427;

    private static final int HEIGHT = 240;
    /** The game's smallest GUI, which the sheet's own screen must also hold every sheet at. */
    private static final int SMALLEST_WIDTH = 320;
    /** The narrowest GUI the catalogue's panels draw at, so the narrowest the sheet opens over them. */
    private static final int NARROWEST = 425;
    /** A GUI scale that leaves the 854 by 480 window a GUI of 213 by 120, too small for the catalogue's consoles. */
    private static final int SMALL_GUI_SCALE = 4;

    /** The first slot of the main inventory, below the hotbar. */
    private static final int INVENTORY_SLOT = 9;

    /** A GUI pixel off the sheet's console, in the window's top left corner. */
    private static final int SCREEN_EDGE = 2;

    private static final int[][] SIZES = {{WIDTH, HEIGHT}, {NARROWEST, HEIGHT}, {SMALLEST_WIDTH, HEIGHT}};

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        inTheRealGame(context);
    }

    /** The mod's item with this path. Read when a test runs: a static field can load before the mod registers its items. */
    private static Item item(String path) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        require(item != Items.AIR, "the mod registers an item " + path);
        return item;
    }

    private static List<Item> everyItemASheetIsChecked() {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (SpecSheets.isCompany(item)) {
                items.add(item);
            }
        }
        // Stock items: one that is fuel, one that wears, one with neither, and the longest name in the vanilla set
        // that matters.
        items.addAll(List.of(Items.COAL, Items.IRON_PICKAXE, Items.DIAMOND_SWORD, Items.CRAFTING_TABLE));
        return items;
    }

    /** What a sheet of {@code item} draws as text: its title, what it is and each rating's name and figure. */
    private static List<String> expectedStrings(Item item) {
        SpecSheet sheet = SpecSheets.of(item);
        List<String> expected = new ArrayList<>();
        expected.add(new ItemStack(item).getHoverName().getString().toUpperCase(Locale.ROOT));
        expected.add(sheet.kind().getString());
        for (SpecSheet.Rating rating : sheet.ratings()) {
            expected.add(rating.label().getString());
            expected.add(rating.value().getString());
        }
        return expected;
    }

    /** The sheet's text lines drawn by a screen of its own, in the order they are drawn. */
    private static List<Drawn> drawnBySheetScreen(Item item, int width, int height, List<Rect> glassOut) {
        SpecSheetScreen screen = new SpecSheetScreen(item);
        screen.init(width, height);
        glassOut.add(Objects.requireNonNull(screen.glass(), "the sheet is laid out once the screen has opened"));
        return DrawnText.of(screen);
    }

    // ---------------------------------------------------------------- the sheet, with no game running

    private static void everySheetLiesInsideItsGlass(Minecraft client) {
        List<Item> items = everyItemASheetIsChecked();
        for (int[] size : SIZES) {
            for (Item item : items) {
                String what = BuiltInRegistries.ITEM.getKey(item) + " at " + size[0] + " by " + size[1];
                List<Rect> glass = new ArrayList<>();
                List<Drawn> drawn = drawnBySheetScreen(item, size[0], size[1], glass);
                List<String> texts = drawn.stream().map(Drawn::text).toList();
                for (Drawn text : drawn) {
                    Rect box = new Rect(
                            text.box().x(),
                            text.box().y(),
                            text.box().width(),
                            text.box().height());
                    require(
                            inside(box, glass.getFirst()) || isOnThePlate(text),
                            "text '" + text.shortText() + "' at " + box + " is outside the glass " + glass.getFirst()
                                    + " of " + what);
                }
                for (String expected : expectedStrings(item)) {
                    require(
                            texts.contains(expected),
                            "the sheet of " + what + " draws '" + expected + "' whole, it draws " + texts);
                }
                requireTitleAndCellClearOfTheCorners(client, drawn, glass.getFirst(), what);
                String blurb = SpecSheets.of(item).blurb().getString();
                String drawnBlurb = String.join(" ", texts);
                for (String word : Splitter.on(' ').split(blurb)) {
                    require(
                            drawnBlurb.contains(word),
                            "the blurb of " + what + " is drawn whole: '" + word + "' is missing, it draws " + texts);
                }
            }
        }
        List<Rect> glass = new ArrayList<>();
        drawnBySheetScreen(Items.DIAMOND_SWORD, WIDTH, HEIGHT, glass);
        drawnBySheetScreen(item("part_drill"), WIDTH, HEIGHT, glass);
        require(
                glass.get(0).height() < glass.get(1).height(),
                "a short sheet is a short console and a long one is taller, the glasses are " + glass);
        System.out.println("[spec-sheet] checked " + items.size() + " sheets at " + SIZES.length + " sizes");
    }

    /**
     * The glass has rounded corners and a dark ring at its edge (#984): the sheet's title, which sits in the glass's top rows, and the
     * item's cell and icon under it must be clear of both, as the catalogue's own are. The title is the first string drawn in the glass;
     * the cell stands where {@code SpecSheetLayout} puts it, under the title's rule.
     */
    private static void requireTitleAndCellClearOfTheCorners(
            Minecraft client, List<Drawn> drawn, Rect glass, String what) {
        CatalogueLook look = CatalogueLook.current();
        int ring = look.casing().vignetteWidth();
        Drawn title = drawn.stream()
                .filter(text -> inside(boxOf(text), glass))
                .min(java.util.Comparator.comparingInt(text -> text.box().y()))
                .orElseThrow(() -> new AssertionError("the sheet of " + what + " draws nothing in its glass"));
        requireClearOfCorners(boxOf(title), glass, ring, "the title '" + title.shortText() + "' of " + what);
        int top = glass.y()
                + look.titleTop()
                + client.font.lineHeight
                + CrtTuning.current().headerRuleGap()
                + 1
                + look.titleGap();
        Rect cell = new Rect(glass.x() + look.pad(), top, look.slotSize(), look.slotSize());
        requireClearOfCorners(cell, glass, 0, "the item cell " + cell + " of " + what);
        Rect icon = new Rect(
                cell.x() + look.iconInset(),
                cell.y() + look.iconInset(),
                look.slotSize() - 2 * look.iconInset(),
                look.slotSize() - 2 * look.iconInset());
        requireClearOfCorners(icon, glass, ring, "the item icon in " + cell + " of " + what);
    }

    private static Rect boxOf(Drawn text) {
        return new Rect(
                text.box().x(), text.box().y(), text.box().width(), text.box().height());
    }

    /** The legends of the Recipes link and the close switch are drawn on their plates, under the glass. */
    private static boolean isOnThePlate(Drawn text) {
        return text.text().equals("CLOSE") || text.text().equals("RECIPES");
    }

    private static void theRatingsAreTheGamesOwnNumbers() {
        UpgradeTuning tuning = UpgradeTuning.DEFAULT;
        List<String> drill = textsOf(item("part_drill"));
        for (int tier = 1; tier <= ComponentTrack.DRILL.maxTier(); tier++) {
            long price = tuning.price(ComponentTrack.DRILL, tier);
            String figure = "SPEED x" + figure(tuning.ratio(ComponentTrack.DRILL, tier));
            require(
                    drill.contains(figure),
                    "the drill part reads tier " + tier + "'s " + figure + ", it draws " + drill);
            require(
                    drill.contains(price == 0 ? "T" + tier + " CRAFTED" : "T" + tier + " $" + figure(price)),
                    "the drill part reads tier " + tier + "'s price of $" + price + ", it draws " + drill);
        }
        // The figures themselves, written out, so a tuning that moves must move this too.
        require(
                drill.contains("SPEED x2") && drill.contains("T2 $500") && drill.contains("SPEED x6"),
                "the drill part reads tier 2 as SPEED x2 at $500 and tier 6 as SPEED x6, it draws " + drill);
        // The scanner's figures are the area gameplay scans (ScannerTuning.area), 32 down at tier 1 and 1.5 times that
        // at tier 2,
        // not the tuning's raw 0.5 and 0.75.
        List<String> scanner = textsOf(item("part_scanner"));
        require(
                scanner.contains("32 DOWN") && scanner.contains("48 DOWN") && scanner.contains("96 DOWN"),
                "the scanner part reads 32, 48 and up to 96 DOWN, it draws " + scanner);
        require(
                textsOf(item("scanner_unit")).contains("32 DOWN"),
                "the scanner unit fits as tier 1, 32 DOWN, it draws " + textsOf(item("scanner_unit")));
        require(
                textsOf(item("part_hull")).containsAll(List.of("100 HP", "300 HP")),
                "a stock hull is 100 HP and tier 2 is 300, it draws " + textsOf(item("part_hull")));
        require(
                textsOf(item("part_radiator")).contains("HEAT x0.75"),
                "the radiator's tier 2 lowers the heat to x0.75");
        require(
                textsOf(RepairRegistry.item(Consumable.BLASTING_CHARGE)).containsAll(List.of("$40", "2 SLABS", "3 S")),
                "a blasting charge reads its $40, the 2 slabs it clears and its 3 second fuse, it draws "
                        + textsOf(RepairRegistry.item(Consumable.BLASTING_CHARGE)));
        require(
                textsOf(item("goldium")).contains("$250"),
                "goldium reads the $250 a terminal pays, it draws " + textsOf(item("goldium")));
        String coalStack = Integer.toString(Items.COAL.getDefaultMaxStackSize());
        require(
                textsOf(Items.COAL).containsAll(List.of("STOCK ITEM", "POD FUEL", "YES", coalStack)),
                "coal is stock, pod fuel, and stacks to " + coalStack + ", it draws " + textsOf(Items.COAL));
        require(
                textsOf(Items.IRON_PICKAXE).contains("250"),
                "an iron pickaxe reads its 250 durability, it draws " + textsOf(Items.IRON_PICKAXE));
    }

    private static List<String> textsOf(Item item) {
        return drawnBySheetScreen(item, WIDTH, HEIGHT, new ArrayList<>()).stream()
                .map(Drawn::text)
                .toList();
    }

    private static String figure(float value) {
        float rounded = Math.round(value * 100f) / 100f;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Float.toString(rounded);
    }

    private static String figure(long dollars) {
        return String.format(Locale.ROOT, "%,d", dollars);
    }

    private static void everyCompanyItemSpeaksInTheCompanysVoice() {
        for (Item item : BuiltInRegistries.ITEM) {
            if (!SpecSheets.isCompany(item)) {
                continue;
            }
            String blurb = SpecSheets.of(item).blurb().getString();
            require(
                    !blurb.startsWith("deepcharter.spec."),
                    BuiltInRegistries.ITEM.getKey(item) + " has no blurb in the lang file, it reads '" + blurb + "'");
            require(
                    blurb.length() <= 70,
                    BuiltInRegistries.ITEM.getKey(item) + "'s blurb is short, this one is " + blurb.length()
                            + " characters: " + blurb);
        }
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
            // Item components are bound only once a world is loaded, so a sheet cannot be built before it.
            context.runOnClient(client -> {
                everySheetLiesInsideItsGlass(client);
                theRatingsAreTheGamesOwnNumbers();
                everyCompanyItemSpeaksInTheCompanysVoice();
            });
            context.runOnClient(client ->
                    CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook()));
            CatalogueScene.openInventory(context);
            theSpecSheetButtonOpensTheSheet(context);
            theRecipesLinkOpensTheRecipes(context);
            theRecipesLinkIsDeadInAWindowTooSmallForTheCatalogue(context);
            theSheetTakesEveryKeyAndHidesTheTooltipUnderIt(context, singleplayer);
            context.setScreen(() -> null);
            theKeyDoesNothingBeforeAnySheetIsOffered(context);
            aFirstCraftOpensNoSheetAndShowsASlip(context, singleplayer);
            theSlipIsClearOfEveryToastAndTheDirectiveSlip(context, singleplayer);
            theKeyOpensTheMostRecentSheet(context, singleplayer);
            aKeyPressedSeveralTimesOpensOneSheet(context);
            aSheetWaitsForAScreenItCannotHold(context, singleplayer);
            sheetsAskedForInARowShowOneAfterTheOther(context);
            context.setScreen(() -> null);
        }
    }

    private static void theSpecSheetButtonOpensTheSheet(ClientGameTestContext context) {
        AbstractContainerScreen<?> screen = Objects.requireNonNull(
                context.computeOnClient(client -> (AbstractContainerScreen<?>) client.gui.screen()),
                "the inventory is open");
        CataloguePanel panel = CatalogueScene.panel(context);
        Item drill = HangarParts.DRILL_HEAD;
        context.runOnClient(client -> panel.state().look(panel.catalogue(), drill, CatalogueState.Mode.RECIPES));
        context.waitTicks(1);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        require(context.computeOnClient(client -> panel.sheetItem()) == null, "no sheet is open before the button");
        CatalogueScene.hover(context, layout.card().spec());
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        require(
                context.computeOnClient(client -> panel.sheetItem()) == drill,
                "SPEC SHEET opens the sheet of the item looked up");
        sheetIsWholeInsideItsGlass(context, screen, panel, drill, "from the button at the default window");

        // The sheet sizes itself to the window, and the panels draw down to the narrowest window.
        for (int width : new int[] {NARROWEST, WIDTH}) {
            context.runOnClient(client -> screen.resize(width, HEIGHT));
            context.waitTicks(2);
            require(
                    context.computeOnClient(client -> panel.sheetItem()) == drill,
                    "a resize keeps the sheet open at " + width);
            sheetIsWholeInsideItsGlass(context, screen, panel, drill, "at " + width + " by " + HEIGHT);
        }

        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(1);
        require(context.computeOnClient(client -> panel.sheetItem()) == null, "Escape puts the sheet away");
        require(
                context.computeOnClient(client -> client.gui.screen()) == screen,
                "and does not close the inventory the first time");
        require(
                context.computeOnClient(client -> panel.showsRecipePanel()),
                "the recipe panel the sheet was opened from is as it was");

        CatalogueScene.hover(context, layout.card().spec());
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        require(context.computeOnClient(client -> panel.sheetItem()) == drill, "the button opens it again");
        // A click on the sheet's text does not lose it; the close switch puts it away.
        CatalogueScene.hover(context, Objects.requireNonNull(context.computeOnClient(client -> panel.sheetGlass())));
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(
                context.computeOnClient(client -> panel.sheetItem()) == drill,
                "a click on the sheet's glass leaves the sheet open");
        CatalogueScene.hover(
                context, Objects.requireNonNull(context.computeOnClient(client -> panel.sheetCloseSwitch())));
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(context.computeOnClient(client -> panel.sheetItem()) == null, "the close switch puts the sheet away");
        require(
                context.computeOnClient(client -> client.gui.screen()) == screen,
                "the click is the sheet's own, and the inventory stays open");

        // A click off the console, on the screen's edge, puts it away too.
        context.runOnClient(client -> SpecSheetOpener.show(drill));
        ClientWait.until(context, "the sheet again", client -> panel.sheetItem() == drill);
        ClientCursor.moveTo(context, SCREEN_EDGE, SCREEN_EDGE);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(
                context.computeOnClient(client -> panel.sheetItem()) == null,
                "a click off the sheet's console puts it away");
    }

    /** The Recipes link of a sheet shows the item's recipes in the catalogue: over the inventory, over hidden panels, and from a sheet of its own. */
    private static void theRecipesLinkOpensTheRecipes(ClientGameTestContext context) {
        Item drill = HangarParts.DRILL_HEAD;
        CatalogueScene.openInventory(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        context.runOnClient(client -> panel.state().clear());
        context.runOnClient(client -> SpecSheetOpener.show(drill));
        ClientWait.until(context, "the sheet over the inventory", client -> panel.sheetItem() == drill);
        require(
                context.computeOnClient(client -> panel.state().lookup()) == null,
                "setup: nothing is looked up before the link");
        CatalogueScene.hover(
                context, Objects.requireNonNull(context.computeOnClient(client -> panel.sheetRecipesLink())));
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(context.computeOnClient(client -> panel.sheetItem()) == null, "the Recipes link puts the sheet away");
        CatalogueState.Lookup lookup =
                context.computeOnClient(client -> panel.state().lookup());
        require(
                lookup != null && lookup.item() == drill && lookup.mode() == CatalogueState.Mode.RECIPES,
                "and shows the item's recipes, got " + lookup);
        require(context.computeOnClient(client -> panel.showsRecipePanel()), "in the recipe console");

        // Over panels that were put away, the link brings them back.
        context.runOnClient(client -> {
            panel.state().clear();
            panel.state().toggleHidden();
        });
        context.runOnClient(client -> SpecSheetOpener.show(drill));
        ClientWait.until(context, "the sheet over the hidden panels", client -> panel.sheetItem() == drill);
        require(context.computeOnClient(client -> !panel.panelsShown()), "setup: the panels are put away");
        CatalogueScene.hover(
                context, Objects.requireNonNull(context.computeOnClient(client -> panel.sheetRecipesLink())));
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(
                context.computeOnClient(client -> panel.panelsShown() && panel.showsRecipePanel()),
                "the link unhides the panels and shows the recipes");
        context.runOnClient(client -> panel.state().clear());

        // A sheet of its own, with no inventory open: the link opens the inventory at the recipes.
        context.setScreen(() -> null);
        context.runOnClient(client -> SpecSheetOpener.show(drill));
        ClientWait.screen(context, SpecSheetScreen.class);
        context.waitTicks(2);
        CatalogueScene.hover(
                context,
                Objects.requireNonNull(context.computeOnClient(
                        client -> ((SpecSheetScreen) Objects.requireNonNull(client.gui.screen())).recipesLink())));
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        ClientWait.screen(context, InventoryScreen.class);
        context.waitTicks(3);
        CataloguePanel opened = CatalogueScene.panel(context);
        CatalogueState.Lookup there =
                context.computeOnClient(client -> opened.state().lookup());
        require(
                there != null && there.item() == drill && there.mode() == CatalogueState.Mode.RECIPES,
                "from a sheet of its own the link opens the inventory at the recipes, got " + there);
        context.runOnClient(client -> opened.state().clear());
    }

    /** A window too small for the catalogue has nothing for the link to open: it is drawn dim, and a click does nothing and keeps the sheet. */
    private static void theRecipesLinkIsDeadInAWindowTooSmallForTheCatalogue(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.runOnClient(client -> client.getWindow().setGuiScale(SMALL_GUI_SCALE));
        try {
            context.runOnClient(client -> SpecSheetOpener.show(HangarParts.DRILL_HEAD));
            ClientWait.screen(context, SpecSheetScreen.class);
            context.waitTicks(2);
            require(
                    context.computeOnClient(client ->
                            !((SpecSheetScreen) Objects.requireNonNull(client.gui.screen())).recipesLinkActive()),
                    "setup: the window is too small for the catalogue, so the link is dim");
            CatalogueScene.hover(
                    context,
                    Objects.requireNonNull(context.computeOnClient(
                            client -> ((SpecSheetScreen) Objects.requireNonNull(client.gui.screen())).recipesLink())));
            RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
            context.waitTicks(3);
            require(
                    context.computeOnClient(client -> client.gui.screen() instanceof SpecSheetScreen),
                    "a click on the dead link does nothing: the sheet is still open");
        } finally {
            context.runOnClient(
                    client -> client.getWindow().setGuiScale(client.getWindow().calculateScale(0, false)));
            context.setScreen(() -> null);
        }
    }

    /** Draws {@code screen} and holds the strings of {@code item}'s sheet to the sheet's glass. */
    private static void sheetIsWholeInsideItsGlass(
            ClientGameTestContext context,
            AbstractContainerScreen<?> screen,
            CataloguePanel panel,
            Item item,
            String when) {
        CatalogueScene.parkCursor(context, CatalogueScene.layout(context, panel));
        context.runOnClient(client -> {
            Rect glass = Objects.requireNonNull(panel.sheetGlass(), () -> "the sheet is laid out " + when);
            List<Drawn> drawn = DrawnText.of(screen);
            List<String> texts = drawn.stream().map(Drawn::text).toList();
            // The recipe panel under the sheet draws the item's name too, so a string counts when one copy of it is in
            // the glass.
            for (String expected : expectedStrings(item)) {
                require(texts.contains(expected), "the sheet draws '" + expected + "' " + when + ", it draws " + texts);
                require(
                        drawn.stream()
                                .filter(text -> text.text().equals(expected))
                                .anyMatch(text -> inside(
                                        new Rect(
                                                text.box().x(),
                                                text.box().y(),
                                                text.box().width(),
                                                text.box().height()),
                                        glass)),
                        "'" + expected + "' is not inside the sheet's glass " + glass + " " + when);
            }
        });
    }

    private static void offer(TestSingleplayerContext singleplayer, Item item, boolean expected) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            require(
                    SpecSheetPopups.offer(player, item) == expected,
                    "the server " + (expected ? "shows" : "does not show") + " the sheet of "
                            + BuiltInRegistries.ITEM.getKey(item));
        });
    }

    /** What the screen draws with the mouse where it really is. */
    private static List<Drawn> drawnAtTheMouse(Minecraft client, Screen screen) {
        var window = client.getWindow();
        return DrawnText.of(screen, (int) client.mouseHandler.getScaledXPos(window), (int)
                client.mouseHandler.getScaledYPos(window));
    }

    private static void theSheetTakesEveryKeyAndHidesTheTooltipUnderIt(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runCommand("clear @p");
        singleplayer.getServer().runCommand("item replace entity @p inventory.0 with minecraft:cobblestone 3");
        ClientWait.until(
                context,
                "the cobblestone in the first inventory slot",
                client -> ClientSession.player(client)
                        .getInventory()
                        .getItem(INVENTORY_SLOT)
                        .is(Items.COBBLESTONE));
        CatalogueScene.openInventory(context);
        AbstractContainerScreen<?> screen = Objects.requireNonNull(
                context.computeOnClient(client -> (AbstractContainerScreen<?>) client.gui.screen()),
                "the inventory is open");
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        net.minecraft.world.inventory.Slot slot = screen.getMenu().slots.get(INVENTORY_SLOT);
        Rect cell = new Rect(layout.inventory().x() + slot.x, layout.inventory().y() + slot.y, 16, 16);
        CatalogueScene.hover(context, cell);
        context.waitTicks(2);

        // The control: with no sheet open the slot's tooltip is drawn, so a missing tooltip below means the sheet
        // hid it.
        require(
                context.computeOnClient(client -> drawnAtTheMouse(client, screen).stream()
                        .anyMatch(text -> text.text().equals("Cobblestone"))),
                "with no sheet the hovered slot's tooltip names the cobblestone");

        Item teleporter = RepairRegistry.item(Consumable.QUANTUM_TELEPORTER);
        context.runOnClient(client -> SpecSheetOpener.show(teleporter));
        ClientWait.until(context, "the sheet over the inventory", client -> panel.sheetItem() == teleporter);
        require(
                context.computeOnClient(client -> drawnAtTheMouse(client, screen).stream()
                        .noneMatch(text -> text.text().equals("Cobblestone"))),
                "the slot under the sheet shows no tooltip over it");
        context.getInput().pressKey(InputConstants.KEY_1);
        context.waitTicks(3);
        require(
                context.computeOnClient(client -> ClientSession.player(client)
                                .getInventory()
                                .getItem(INVENTORY_SLOT)
                                .is(Items.COBBLESTONE)
                        && !ClientSession.player(client)
                                .getInventory()
                                .getItem(0)
                                .is(Items.COBBLESTONE)),
                "a hotbar key with the sheet open does not swap the slot under it, the slots are "
                        + context.computeOnClient(client ->
                                ClientSession.player(client).getInventory().getNonEquipmentItems()));
        require(context.computeOnClient(client -> panel.sheetItem() == null), "the key puts the sheet away");
        require(context.computeOnClient(client -> client.gui.screen() == screen), "and the inventory stays open");

        // The control again: the same key with no sheet does swap, so the check above can fail.
        context.getInput().pressKey(InputConstants.KEY_1);
        ClientWait.until(
                context,
                "the cobblestone swapped into the first hotbar slot",
                client -> ClientSession.player(client).getInventory().getItem(0).is(Items.COBBLESTONE));
        singleplayer.getServer().runCommand("clear @p");
    }

    private static void sheetsAskedForInARowShowOneAfterTheOther(ClientGameTestContext context) {
        Item first = RepairRegistry.item(Consumable.PLASTIC_EXPLOSIVES);
        Item second = RepairRegistry.item(Consumable.MATTER_TRANSMITTER);
        CatalogueScene.openInventory(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        context.runOnClient(client -> {
            SpecSheetOpener.show(first);
            SpecSheetOpener.show(second);
        });
        ClientWait.until(context, "the first sheet", client -> panel.sheetItem() == first);
        context.waitTicks(10);
        require(
                context.computeOnClient(client -> panel.sheetItem() == first),
                "the second sheet waits and does not take the first one's place");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(
                context, "the second sheet after the first is put away", client -> panel.sheetItem() == second);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the sheets put away", client -> panel.sheetItem() == null);
        require(
                context.computeOnClient(client -> client.gui.screen() instanceof InventoryScreen),
                "the inventory stays open");

        // With no screen open they follow in order on screens of their own.
        context.setScreen(() -> null);
        Item engine = ComponentItems.item(ComponentTrack.ENGINE);
        Item cargo = ComponentItems.item(ComponentTrack.CARGO_BAY);
        context.runOnClient(client -> {
            SpecSheetOpener.show(engine);
            SpecSheetOpener.show(cargo);
        });
        ClientWait.until(
                context,
                "the engine part's sheet first",
                client -> client.gui.screen() instanceof SpecSheetScreen screen && sheetScreenShows(screen, engine));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(
                context,
                "the cargo bay part's sheet after it",
                client -> client.gui.screen() instanceof SpecSheetScreen screen && sheetScreenShows(screen, cargo));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "both sheets put away", client -> client.gui.screen() == null);
    }

    /** With no sheet offered yet, the key has nothing to open and opens nothing. */
    private static void theKeyDoesNothingBeforeAnySheetIsOffered(ClientGameTestContext context) {
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        // tick-wait: an absence has nothing to await, so the key is given a few ticks to open something
        context.waitTicks(5);
        require(
                context.computeOnClient(client -> client.gui.screen() == null),
                "with no sheet offered the key opens no screen");
    }

    /** A first craft or buy sends the sheet: a corner slip names the item and the key, and no screen opens. */
    private static void aFirstCraftOpensNoSheetAndShowsASlip(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        // Over the catalogue: where a player is when they craft.
        CatalogueScene.openInventory(context);
        CataloguePanel panel = CatalogueScene.panel(context);
        Item dynamite = RepairRegistry.item(Consumable.DYNAMITE);
        offer(singleplayer, dynamite, true);
        ClientWait.until(
                context,
                "the slip over the catalogue",
                client -> slipShowing(client, dynamite).isPresent());
        // tick-wait: an absence has nothing to await, so the sheet is given the ticks the old popup took to open
        context.waitTicks(10);
        require(
                context.computeOnClient(client -> panel.sheetItem() == null),
                "a first craft opens no sheet over the catalogue");
        require(
                context.computeOnClient(client -> client.gui.screen() instanceof InventoryScreen),
                "the inventory is as it was");
        require(
                context.computeOnClient(client -> drawnByToasts(client).stream()
                        .anyMatch(text -> text.text()
                                .contains("["
                                        + CatalogueKeys.SPEC_SHEET
                                                .getTranslatedKeyMessage()
                                                .getString() + "]"))),
                "the slip names the key");
        offer(singleplayer, dynamite, false);

        // With no screen open: no screen of its own either.
        context.setScreen(() -> null);
        Item charge = RepairRegistry.item(Consumable.SHAPED_CHARGE);
        offer(singleplayer, charge, true);
        ClientWait.until(
                context,
                "the slip with no screen open",
                client -> slipShowing(client, charge).isPresent());
        // tick-wait: an absence has nothing to await, so the sheet is given the ticks the old popup took to open
        context.waitTicks(10);
        require(
                context.computeOnClient(client -> client.gui.screen() == null),
                "a first craft opens no screen of its own");
        context.runOnClient(client -> client.gui.toastManager().clear());
    }

    /** The slip is clear of the HUD, of a vanilla toast and of a directive slip shown at the same time. */
    private static void theSlipIsClearOfEveryToastAndTheDirectiveSlip(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        Item part = ComponentItems.item(ComponentTrack.HULL);
        StepToast[] directive = {null};
        context.runOnClient(client -> {
            client.gui.toastManager().addToast(new StepToastClearOfSonarClientTest.SlotToast());
            directive[0] = StepToast.of(Component.literal("Find the Prospector."));
            client.gui.toastManager().addToast(directive[0]);
        });
        offer(singleplayer, part, true);
        ClientWait.until(
                context,
                "the slip, the directive slip and the vanilla toast all placed",
                client -> slipShowing(client, part).isPresent()
                        && directive[0].placement().isPresent()
                        && HudKeepClear.rects(
                                        client,
                                        client.getWindow().getGuiScaledWidth(),
                                        client.getWindow().getGuiScaledHeight())
                                .stream()
                                .anyMatch(rect -> rect.what().equals("vanilla toast")));
        context.runOnClient(client -> {
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            StepToast slip = slipShowing(client, part).orElseThrow();
            Box box = StepToastClearOfHudClientTest.box(slip.placement().orElseThrow());
            Box directiveBox =
                    StepToastClearOfHudClientTest.box(directive[0].placement().orElseThrow());
            require(
                    !ClientChecks.overlaps(box, directiveBox),
                    "the slip at " + box + " covers the directive slip at " + directiveBox);
            for (HudKeepClear.Rect kept : HudKeepClear.rects(client, width, height)) {
                require(
                        !ClientChecks.overlaps(box, StepToastClearOfHudClientTest.box(kept)),
                        "the slip at " + box + " covers the '" + kept.what() + "' at "
                                + StepToastClearOfHudClientTest.box(kept));
            }
            for (Map.Entry<String, Box> hud : StepToastClearOfHudClientTest.critical(client, width, height)
                    .entrySet()) {
                require(
                        !ClientChecks.overlaps(box, hud.getValue()),
                        "the slip at " + box + " covers the " + hud.getKey() + " at " + hud.getValue());
            }
            require(
                    drawnByToasts(client).stream().anyMatch(text -> ClientChecks.overlaps(box, text.box())),
                    "the toast manager draws the slip's text inside its box");
            client.gui.toastManager().clear();
        });
    }

    /** The key opens the sheet of the item most recently offered, not the first. */
    private static void theKeyOpensTheMostRecentSheet(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        Item older = ComponentItems.item(ComponentTrack.DRILL);
        Item latest = ComponentItems.item(ComponentTrack.FUEL_TANK);
        offer(singleplayer, older, true);
        offer(singleplayer, latest, true);
        ClientWait.until(
                context,
                "the slip of the latest sheet",
                client -> slipShowing(client, latest).isPresent());
        context.getInput().pressKey(CatalogueKeys.SPEC_SHEET);
        ClientWait.until(
                context,
                "the key's sheet on a screen of its own",
                client -> client.gui.screen() instanceof SpecSheetScreen screen && sheetScreenShows(screen, latest));
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the sheet put away", client -> client.gui.screen() == null);
        context.runOnClient(client -> client.gui.toastManager().clear());
    }

    /** Presses the key {@code times} times within one client tick, as a player tapping it fast does. */
    private static void tapTheKey(ClientGameTestContext context, int times) {
        context.runOnClient(client -> {
            for (int i = 0; i < times; i++) {
                KeyMapping.click(CatalogueKeys.SPEC_SHEET.getDefaultKey());
            }
        });
    }

    /** Tapping the key again, while its sheet is open or queued, queues nothing: Escape leaves no sheet open. */
    private static void aKeyPressedSeveralTimesOpensOneSheet(ClientGameTestContext context) {
        context.setScreen(() -> null);
        tapTheKey(context, 1);
        ClientWait.screen(context, SpecSheetScreen.class);
        // The taps land while the sheet is on its screen, and again a tick later.
        tapTheKey(context, 2);
        context.waitTicks(1);
        tapTheKey(context, 1);
        // tick-wait: an absence has nothing to await, so a queued second sheet is given the ticks to come up
        context.waitTicks(10);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the sheet put away", client -> client.gui.screen() == null);
        // tick-wait: an absence has nothing to await, so a queued second sheet is given the ticks to open
        context.waitTicks(10);
        require(
                context.computeOnClient(client -> client.gui.screen() == null),
                "one Escape leaves no sheet open and none re-opens after the taps");
    }

    /** A sheet the key queues never takes the place of a screen that cannot hold it, and shows once that closes. */
    private static void aSheetWaitsForAScreenItCannotHold(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        Item part = ComponentItems.item(ComponentTrack.RADIATOR);
        context.setScreen(() -> new PauseScreen(true));
        ClientWait.screen(context, PauseScreen.class);
        offer(singleplayer, part, true);
        ClientWait.until(
                context,
                "the slip of the sheet",
                client -> slipShowing(client, part).isPresent());
        tapTheKey(context, 2);
        // tick-wait: an absence has nothing to await, so the sheet is given the ticks to take the screen's place
        context.waitTicks(10);
        require(
                context.computeOnClient(client -> client.gui.screen() instanceof PauseScreen),
                "a sheet never takes the place of a screen it cannot hold");
        context.setScreen(() -> null);
        ClientWait.screen(context, SpecSheetScreen.class);
        require(
                context.computeOnClient(client -> sheetScreenShows(Objects.requireNonNull(client.gui.screen()), part)),
                "and shows once that screen has closed");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ClientWait.until(context, "the waiting sheet closed", client -> client.gui.screen() == null);
        // tick-wait: an absence has nothing to await, so a second tap's sheet is given the ticks to open
        context.waitTicks(10);
        require(
                context.computeOnClient(client -> client.gui.screen() == null),
                "the second tap queued no second copy of the sheet");
        context.runOnClient(client -> client.gui.toastManager().clear());
    }

    /** The slip of a sheet of {@code item}: a toast that has a place and draws the item's name inside it. */
    private static Optional<StepToast> slipShowing(Minecraft client, Item item) {
        String name = new ItemStack(item).getHoverName().getString();
        List<Drawn> drawn = drawnByToasts(client);
        for (Object instance : ((ToastManagerAccess) client.gui.toastManager()).deepcharter$visibleToasts()) {
            if (((ToastInstanceAccess) instance).deepcharter$toast() instanceof StepToast toast
                    && toast.placement().isPresent()) {
                Box slip = StepToastClearOfHudClientTest.box(toast.placement().get());
                if (drawn.stream()
                        .anyMatch(text -> text.text().contains(name) && ClientChecks.overlaps(slip, text.box()))) {
                    return Optional.of(toast);
                }
            }
        }
        return Optional.empty();
    }

    private static List<Drawn> drawnByToasts(Minecraft client) {
        return DrawnText.of(graphics -> client.gui.toastManager().extractRenderState(graphics));
    }

    private static boolean sheetScreenShows(Screen screen, Item item) {
        List<Drawn> drawn = DrawnText.of(screen);
        String title = new ItemStack(item).getHoverName().getString().toUpperCase(Locale.ROOT);
        return drawn.stream().anyMatch(text -> text.text().equals(title));
    }
}
