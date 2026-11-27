package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState.Mode;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.PartsCatalogue;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.RecipeConsoleScreen;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;

/**
 * Client GameTest for #1378: the recipe console's total materials. The switch first: M shows them for the recipe shown, and any other
 * lookup (another item, back, put away) shows the recipe again. Then the real game, holding two iron and two copper and knowing only the
 * {@link CatalogueFixtures}: M on the drill head's recipe lists raw iron and redstone short (the iron it lacks smelted from raw iron, the
 * two it holds taken), then the iron and the copper it holds; a row is an item R looks up; M again shows the recipe. The hub, all held,
 * says so, and follows the inventory as the server changes it; an item with no known recipe has nothing to break down; seven materials scroll under the wheel; and a chest's console has M
 * too.
 */
public class CatalogueMaterialsClientTest implements FabricClientGameTest {
    private static final Item DRILL = HangarParts.DRILL_HEAD;
    private static final Item HUB = HangarParts.ROTOR_HUB;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.runOnClient(client -> theSwitchFollowsTheLookup());
            CataloguePanel panel = CatalogueScene.knowFixturesHoldingIronAndCopper(context, singleplayer);
            mShowsTheTotalMaterials(context, panel);
            everythingHeldIsSaid(context, panel);
            theMaterialsFollowTheInventory(context, singleplayer, panel);
            nothingKnownHasNothingToBreakDown(context, panel);
            aLongListScrolls(context, panel);
            context.setScreen(() -> null);
            theChestConsoleHasThemToo(context, singleplayer);
            context.setScreen(() -> null);
        }
    }

    // ---------------------------------------------------------------- the switch

    private static void theSwitchFollowsTheLookup() {
        PartsCatalogue catalogue = PartsCatalogue.of(CatalogueFixtures.book(), ContextMap.EMPTY);
        CatalogueState state = new CatalogueState();
        state.toggleMaterials(catalogue);
        require(!state.materials(), "with nothing looked up there is nothing to switch");
        state.look(catalogue, DRILL, Mode.RECIPES);
        state.toggleMaterials(catalogue);
        require(state.materials(), "M shows the total materials");
        state.look(catalogue, DRILL, Mode.RECIPES);
        require(state.materials(), "R again on the item shown steps to its next recipe and keeps the materials shown");
        state.look(catalogue, HUB, Mode.RECIPES);
        require(!state.materials(), "another item shows its recipe");
        state.toggleMaterials(catalogue);
        require(state.back() && !state.materials(), "back shows the recipe gone back to");
        state.toggleMaterials(catalogue);
        state.clear();
        require(!state.materials(), "putting the console away shows the recipe next time");
        state.look(catalogue, DRILL, Mode.RECIPES);
        state.toggleMaterials(catalogue);
        state.toggleMaterials(catalogue);
        require(!state.materials(), "M again shows the recipe");
        state.look(catalogue, Items.REDSTONE, Mode.RECIPES);
        state.toggleMaterials(catalogue);
        require(!state.materials(), "with no known recipe shown there is nothing to switch");
    }

    // ---------------------------------------------------------------- the real game

    private static Screen screen(ClientGameTestContext context) {
        return Objects.requireNonNull(context.computeOnClient(client -> client.gui.screen()), "a screen is open");
    }

    private static List<Drawn> drawn(ClientGameTestContext context, Screen screen) {
        return context.computeOnClient(client -> DrawnText.atCursor(screen));
    }

    private static Optional<Drawn> find(List<Drawn> drawn, String text) {
        return drawn.stream().filter(line -> line.text().equals(text)).findFirst();
    }

    private static Drawn requireDrawn(List<Drawn> drawn, String text, String what) {
        return find(drawn, text)
                .orElseThrow(() -> new AssertionError(what + ": '" + text + "' is not drawn, drew "
                        + drawn.stream().map(Drawn::text).toList()));
    }

    private static boolean inside(Drawn text, Rect glass) {
        return text.box().x() >= glass.x()
                && text.box().right() <= glass.right()
                && text.box().y() >= glass.y()
                && text.box().bottom() <= glass.bottom();
    }

    private static void mShowsTheTotalMaterials(ClientGameTestContext context, CataloguePanel panel) {
        Screen screen = screen(context);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.pressOn(context, panel, DRILL, InputConstants.KEY_R);
        CatalogueScene.parkCursor(context, layout);
        require(find(drawn(context, screen), "CRAFTING TABLE").isPresent(), "the drill head's recipe is shown first");
        RealInput.key(context, InputConstants.KEY_M, 0);
        require(context.computeOnClient(client -> panel.state().materials()), "M switches to the total materials");

        List<Drawn> drawn = drawn(context, screen);
        requireDrawn(drawn, "TOTAL MATERIALS", "the heading stands where the station did");
        require(find(drawn, "CRAFTING TABLE").isEmpty(), "the recipe is not drawn under the materials");
        // Six iron, a redstone and a copper. Two iron are held; the other four are smelted from four raw iron, which
        // the player lacks. What is short comes first.
        List<String> rows = List.of("0/4", "0/1", "2/2", "2/1");
        int lastY = Integer.MIN_VALUE;
        for (String row : rows) {
            Drawn figures = requireDrawn(drawn, row, "the materials of the drill head, have/need");
            require(figures.box().y() > lastY, "the rows stand in the order " + rows + ", '" + row + "' is out of it");
            require(inside(figures, layout.recipeGlass()), "'" + row + "' is on the recipe console's glass");
            lastY = figures.box().y();
        }
        int glow = context.computeOnClient(client -> CrtTuning.current().phosphorColor());
        int dim = context.computeOnClient(client -> PanelLook.current().dimTextColor());
        Drawn rawShort = requireDrawn(drawn, "-4", "the raw iron is four short");
        require(rawShort.color() == glow, "what is short glows");
        require(
                rawShort.box().y()
                        == requireDrawn(drawn, "0/4", "the raw iron").box().y(),
                "the shortfall stands on its item's row");
        require(
                requireDrawn(drawn, "-1", "the redstone is one short").color() == glow,
                "the redstone's shortfall glows");
        require(requireDrawn(drawn, "2/2", "the iron").color() == dim, "what is held is dim");
        require(find(drawn, "-0").isEmpty(), "nothing held is drawn short");

        // The drill head has two recipes; the wheel scrolls its materials and does not step to the other recipe.
        int index = context.computeOnClient(
                client -> Objects.requireNonNull(panel.state().lookup()).index());
        require(
                context.computeOnClient(
                                client -> panel.state().found(panel.catalogue()).size())
                        > 1,
                "the drill head has more than one recipe to step to");
        CatalogueScene.hover(context, CatalogueScene.consoleHeading(layout));
        RealInput.scroll(context, -1);
        require(
                context.computeOnClient(client -> panel.state().materials()
                        && Objects.requireNonNull(panel.state().lookup()).index() == index),
                "the wheel scrolls the materials, not the recipes");

        // A row is an item: its name on hover, and R on it looks it up and shows its recipe.
        CatalogueScene.hover(context, CatalogueScene.firstMaterialsRow(layout));
        requireDrawn(drawn(context, screen), "RAW IRON", "the first row names its item on hover");
        RealInput.key(context, InputConstants.KEY_R, 0);
        CatalogueState.Lookup now =
                context.computeOnClient(client -> panel.state().lookup());
        require(
                now != null && now.item() == Items.RAW_IRON && now.mode() == Mode.RECIPES,
                "R on the raw iron row looks it up, got " + now);
        require(!context.computeOnClient(client -> panel.state().materials()), "a new lookup shows its recipe");

        CatalogueScene.parkCursor(context, layout);
        RealInput.key(context, InputConstants.KEY_BACKSPACE, 0);
        RealInput.key(context, InputConstants.KEY_M, 0);
        requireDrawn(drawn(context, screen), "TOTAL MATERIALS", "back on the drill head, M shows the materials again");
        RealInput.key(context, InputConstants.KEY_M, 0);
        List<Drawn> recipe = drawn(context, screen);
        requireDrawn(recipe, "CRAFTING TABLE", "M again shows the recipe");
        require(find(recipe, "TOTAL MATERIALS").isEmpty(), "and not the materials");
    }

    private static void everythingHeldIsSaid(ClientGameTestContext context, CataloguePanel panel) {
        Screen screen = screen(context);
        CatalogueScene.pressOn(context, panel, HUB, InputConstants.KEY_R);
        CatalogueScene.parkCursor(context, CatalogueScene.layout(context, panel));
        RealInput.key(context, InputConstants.KEY_M, 0);
        List<Drawn> drawn = drawn(context, screen);
        requireDrawn(drawn, "YOU HAVE IT ALL", "a hub from one iron and two copper, holding two of each");
        require(find(drawn, "TOTAL MATERIALS").isEmpty(), "the heading says it instead");
        requireDrawn(drawn, "2/1", "the iron");
        requireDrawn(drawn, "2/2", "the copper");
        require(drawn.stream().noneMatch(line -> line.text().startsWith("-")), "nothing is short");
    }

    /**
     * With the hub's materials showing, what the server takes and gives back redraws them without M: the tree is built again when the
     * stock changes. Copper teaches nothing new here (the server taught it already), so the catalogue stays the one the tree was built
     * from and only the stock can say the tree is stale.
     */
    private static void theMaterialsFollowTheInventory(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, CataloguePanel panel) {
        Screen screen = screen(context);
        PartsCatalogue catalogue = context.computeOnClient(client -> panel.catalogue());
        singleplayer.getServer().runCommand("clear @p minecraft:copper_ingot 1");
        ClientWait.until(
                context,
                "the hub's copper drawn one short",
                client -> find(DrawnText.atCursor(screen), "-1").isPresent(),
                client -> DrawnText.atCursor(screen).stream()
                        .map(Drawn::text)
                        .toList()
                        .toString());
        requireDrawn(drawn(context, screen), "1/2", "one copper held of the two");
        singleplayer.getServer().runCommand("give @p minecraft:copper_ingot 1");
        ClientWait.until(
                context,
                "the hub all held again",
                client -> find(DrawnText.atCursor(screen), "YOU HAVE IT ALL").isPresent(),
                client -> DrawnText.atCursor(screen).stream()
                        .map(Drawn::text)
                        .toList()
                        .toString());
        require(
                context.computeOnClient(client ->
                        panel.catalogue() == catalogue && panel.state().materials()),
                "the same catalogue throughout, and the materials shown without M");
    }

    private static void nothingKnownHasNothingToBreakDown(ClientGameTestContext context, CataloguePanel panel) {
        Screen screen = screen(context);
        CatalogueScene.pressOn(context, panel, Items.REDSTONE, InputConstants.KEY_R);
        CatalogueScene.parkCursor(context, CatalogueScene.layout(context, panel));
        RealInput.key(context, InputConstants.KEY_M, 0);
        List<Drawn> drawn = drawn(context, screen);
        requireDrawn(drawn, "NO KNOWN RECIPE", "redstone has no known recipe");
        require(find(drawn, "TOTAL MATERIALS").isEmpty(), "so there are no materials to show");
        require(!context.computeOnClient(client -> panel.state().materials()), "and M switches nothing");
    }

    private static void aLongListScrolls(ClientGameTestContext context, CataloguePanel panel) {
        Screen screen = screen(context);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.pressOn(context, panel, Items.LADDER, InputConstants.KEY_R);
        CatalogueScene.hover(context, CatalogueScene.consoleHeading(layout));
        RealInput.key(context, InputConstants.KEY_M, 0);
        int rows = (layout.card().needBottom() - layout.card().gridY())
                / CatalogueLook.current().needRow();
        int total = ((ShapelessCraftingRecipeDisplay)
                        CatalogueFixtures.sundries().display())
                .ingredients()
                .size();
        require(rows < total, "the console has room for fewer rows than the ladder's " + total + " materials, " + rows);
        requireDrawn(drawn(context, screen), "+" + (total - rows + 1) + " MORE", "the last row says how many more");

        for (int i = 0; i < total - rows; i++) {
            RealInput.scroll(context, -1);
        }
        List<Drawn> end = drawn(context, screen);
        require(end.stream().noneMatch(line -> line.text().endsWith("MORE")), "scrolled to the end, all the rest show");
        require(
                end.stream().filter(line -> line.text().equals("0/1")).count() == rows,
                "every row fits and is drawn, got "
                        + end.stream().map(Drawn::text).toList());
        RealInput.scroll(context, 1);
        requireDrawn(drawn(context, screen), "+2 MORE", "a turn up hides the last two under the last row again");
    }

    private static void theChestConsoleHasThemToo(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        ContainerScreen chest = CatalogueScene.openChest(context, singleplayer, List.of(new ItemStack(DRILL)));
        Rect slot = CatalogueScene.slotCell(
                context, chest, candidate -> !(candidate.container instanceof Inventory) && candidate.index == 0);
        CatalogueScene.hover(context, slot);
        RealInput.key(context, InputConstants.KEY_R, 0);
        ClientWait.until(
                context, "the chest's recipe console", client -> client.gui.screen() instanceof RecipeConsoleScreen);
        Screen console = screen(context);
        RealInput.key(context, InputConstants.KEY_M, 0);
        List<Drawn> drawn = drawn(context, console);
        requireDrawn(drawn, "TOTAL MATERIALS", "M on the chest's console");
        requireDrawn(drawn, "-4", "with the same materials");
        RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
        ClientWait.until(context, "the chest back", client -> client.gui.screen() == chest);
    }
}
