package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState.Mode;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.PartsCatalogue;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.RecipeConsoleScreen;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #1266 (b): the recipe history. The model first: every lookup that is left (for another item, the other mode, or
 * put away) is remembered with the recipe it showed, Back walks them newest first, R again on the item shown (the next recipe) adds
 * nothing, and the history keeps at most {@link CatalogueState#HISTORY_DEPTH}. Then the real game: Backspace and the back knob on the
 * catalogue's recipe console walk it in order, the knob is drawn on the console's deck and does nothing with nothing to go back to, and
 * the history outlives the inventory closing and the console put away. The recipe console of a chest (its own screen) has the knob too.
 */
public class CatalogueHistoryClientTest implements FabricClientGameTest {
    private static final Item DRILL = HangarParts.DRILL_HEAD;
    private static final Item HUB = HangarParts.ROTOR_HUB;
    private static final Item IRON = Items.IRON_INGOT;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player's inventory", client -> client.player != null && client.level != null);
            context.runOnClient(client -> {
                backWalksTheHistoryNewestFirst();
                theHistoryIsCapped();
                CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook());
                CatalogueScreens.resetState();
            });
            backspaceAndTheKnobWalkTheHistory(context);
            theHistoryOutlivesTheScreen(context);
            theChestConsoleGoesBackToo(context, singleplayer);
            context.setScreen(() -> null);
        }
    }

    // ---------------------------------------------------------------- the model

    private static void requireAt(CatalogueState state, Item item, Mode mode, String what) {
        CatalogueState.Lookup now = state.lookup();
        require(
                now != null && now.item() == item && now.mode() == mode,
                what + ": expected " + item + " " + mode + ", got " + now);
    }

    private static void backWalksTheHistoryNewestFirst() {
        PartsCatalogue catalogue = PartsCatalogue.of(CatalogueFixtures.book(), ContextMap.EMPTY);
        CatalogueState state = new CatalogueState();
        require(!state.canGoBack() && !state.back(), "a new catalogue has nothing to go back to");
        state.look(catalogue, DRILL, Mode.RECIPES);
        // R again on the item shown steps to its next recipe: the same page, not a new one.
        state.look(catalogue, DRILL, Mode.RECIPES);
        require(Objects.requireNonNull(state.lookup()).index() == 1, "R again shows the drill head's second recipe");
        require(!state.canGoBack(), "stepping through one item's recipes adds nothing to the history");
        state.look(catalogue, HUB, Mode.RECIPES);
        state.look(catalogue, HUB, Mode.USES);
        state.look(catalogue, IRON, Mode.USES);
        state.clear();
        state.look(catalogue, Items.REDSTONE, Mode.USES);

        require(state.back(), "back goes to the recipe put away");
        requireAt(state, IRON, Mode.USES, "the first back");
        require(state.back(), "back again");
        requireAt(state, HUB, Mode.USES, "back across a switch from recipes to uses");
        require(state.back(), "back again");
        requireAt(state, HUB, Mode.RECIPES, "the hub's recipes");
        require(state.back(), "back again");
        requireAt(state, DRILL, Mode.RECIPES, "the oldest");
        require(
                Objects.requireNonNull(state.lookup()).index() == 1,
                "back returns to the recipe that was shown, the drill head's second");
        require(!state.canGoBack() && !state.back(), "the oldest is the end of the history");
        requireAt(state, DRILL, Mode.RECIPES, "a back with nothing left changes nothing");
    }

    private static void theHistoryIsCapped() {
        PartsCatalogue catalogue = PartsCatalogue.of(CatalogueFixtures.book(), ContextMap.EMPTY);
        CatalogueState state = new CatalogueState();
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (items.size() > CatalogueState.HISTORY_DEPTH + 5) {
                break;
            }
            if (item != Items.AIR) {
                items.add(item);
            }
        }
        items.forEach(item -> state.look(catalogue, item, Mode.RECIPES));
        int backs = 0;
        while (state.back()) {
            backs++;
        }
        require(
                backs == CatalogueState.HISTORY_DEPTH,
                "the history keeps the newest " + CatalogueState.HISTORY_DEPTH + ", went back " + backs);
        CatalogueState.Lookup oldest = Objects.requireNonNull(state.lookup());
        require(
                oldest.item() == items.get(items.size() - 1 - CatalogueState.HISTORY_DEPTH),
                "the oldest kept is the one that many before the last, got " + oldest.item());
    }

    // ---------------------------------------------------------------- the real game

    private static CatalogueState.@Nullable Lookup lookup(ClientGameTestContext context, CataloguePanel panel) {
        return context.computeOnClient(client -> panel.state().lookup());
    }

    private static void requireAt(ClientGameTestContext context, CataloguePanel panel, Item item, Mode mode, String what) {
        CatalogueState.Lookup now = lookup(context, panel);
        require(
                now != null && now.item() == item && now.mode() == mode,
                what + ": expected " + item + " " + mode + ", got " + now);
    }

    /** Hovers {@code item}'s cell in the catalogue's grid and presses {@code key}. */
    private static void press(ClientGameTestContext context, CataloguePanel panel, Item item, int key) {
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, item)));
        RealInput.key(context, key, 0);
    }

    private static List<Drawn> drawn(ClientGameTestContext context, Screen screen) {
        return context.computeOnClient(client -> {
            var window = client.getWindow();
            return DrawnText.of(
                    screen,
                    (int) client.mouseHandler.getScaledXPos(window),
                    (int) client.mouseHandler.getScaledYPos(window));
        });
    }

    /** Whether a "<" is drawn inside {@code knob}: the back knob's label. */
    private static boolean knobDrawn(List<Drawn> drawn, Rect knob) {
        return drawn.stream()
                .anyMatch(text -> text.text().equals("<")
                        && text.box().x() >= knob.x()
                        && text.box().right() <= knob.right()
                        && text.box().y() >= knob.y()
                        && text.box().bottom() <= knob.bottom());
    }

    private static void backspaceAndTheKnobWalkTheHistory(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        Screen screen = Objects.requireNonNull(context.computeOnClient(client -> client.gui.screen()), "a screen is open");
        CataloguePanel panel = CatalogueScene.panel(context);
        context.waitTicks(2); // tick-wait: the catalogue lists the fixtures it now knows
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        Rect knob = layout.card().back();

        press(context, panel, DRILL, InputConstants.KEY_R);
        requireAt(context, panel, DRILL, Mode.RECIPES, "R on the drill head");
        require(knobDrawn(drawn(context, screen), knob), "the back knob is drawn on the recipe console's deck");
        CatalogueScene.hover(context, knob);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        requireAt(context, panel, DRILL, Mode.RECIPES, "the knob with nothing to go back to does nothing");
        require(
                context.computeOnClient(client -> client.gui.screen()) == screen,
                "and the click did not reach the inventory behind");

        press(context, panel, HUB, InputConstants.KEY_R);
        press(context, panel, IRON, InputConstants.KEY_U);
        requireAt(context, panel, IRON, Mode.USES, "U on the iron");

        CatalogueScene.parkCursor(context, layout);
        RealInput.key(context, InputConstants.KEY_BACKSPACE, 0);
        requireAt(context, panel, HUB, Mode.RECIPES, "Backspace goes back one");
        CatalogueScene.hover(context, knob);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        requireAt(context, panel, DRILL, Mode.RECIPES, "a click on the knob goes back one more");
        require(
                context.computeOnClient(client -> client.gui.screen()) == screen,
                "neither Backspace nor the knob closed the screen");
    }

    private static void theHistoryOutlivesTheScreen(ClientGameTestContext context) {
        CataloguePanel first = CatalogueScene.panel(context);
        press(context, first, HUB, InputConstants.KEY_U);
        // Escape puts the console away first; the history keeps what it showed.
        RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
        require(lookup(context, first) == null, "Escape put the recipe console away");
        context.setScreen(() -> null);
        context.runOnClient(client -> client.gui.setScreen(new InventoryScreen(ClientSession.player(client))));
        ClientWait.screen(context, InventoryScreen.class);
        CataloguePanel again = CatalogueScene.panel(context);
        press(context, again, Items.REDSTONE, InputConstants.KEY_U);
        CatalogueScene.parkCursor(context, CatalogueScene.layout(context, again));
        RealInput.key(context, InputConstants.KEY_BACKSPACE, 0);
        requireAt(context, again, HUB, Mode.USES, "the reopened inventory goes back to the recipe put away before it closed");
        RealInput.key(context, InputConstants.KEY_BACKSPACE, 0);
        requireAt(context, again, DRILL, Mode.RECIPES, "and on through what the closed inventory showed");
        context.setScreen(() -> null);
    }

    private static void theChestConsoleGoesBackToo(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        ContainerScreen chest = CatalogueScene.openChest(context, singleplayer, List.of(new ItemStack(HUB)));
        Rect slot = CatalogueScene.slotCell(
                context, chest, candidate -> !(candidate.container instanceof Inventory) && candidate.index == 0);
        CatalogueScene.hover(context, slot);
        RealInput.key(context, InputConstants.KEY_R, 0);
        ClientWait.until(context, "the chest's recipe console", client -> client.gui.screen() instanceof RecipeConsoleScreen);
        RecipeConsoleScreen console =
                context.computeOnClient(client -> (RecipeConsoleScreen) Objects.requireNonNull(client.gui.screen()));
        Rect knob = Objects.requireNonNull(
                        context.computeOnClient(client -> console.console()), "the chest's console is laid out")
                .card()
                .back();
        require(knobDrawn(drawn(context, console), knob), "the chest's console has the back knob");
        require(
                titles(context, console).contains("ROTOR HUB"),
                "the chest's console shows the hub, drew " + titles(context, console));
        CatalogueScene.hover(context, knob);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(
                titles(context, console).contains("DRILL HEAD"),
                "the knob goes back to what the inventory showed last, drew " + titles(context, console));
        RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
        ClientWait.until(context, "the chest back", client -> client.gui.screen() == chest);
        context.setScreen(() -> null);
    }

    private static List<String> titles(ClientGameTestContext context, Screen screen) {
        return drawn(context, screen).stream().map(Drawn::text).toList();
    }
}
