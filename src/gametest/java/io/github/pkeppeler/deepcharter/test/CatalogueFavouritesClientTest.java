package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueFavourites;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueKeys;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueScreens;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueState;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnText;
import io.github.pkeppeler.deepcharter.test.support.DrawnText.Drawn;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * Client GameTest for #1266 (c): favourites. With none starred the favourites row is still there, with a hint that names the key bound
 * now and the star. A on a hovered item stars it and a click on the star of the recipe console takes the star off and puts it back; the
 * star is drawn full for a favourite and hollow for any other item, and each change is in the favourites file at once, read back from disk
 * the way {@link CatalogueFavourites} keeps it.
 */
public class CatalogueFavouritesClientTest implements FabricClientGameTest {
    private static final Item DRILL = HangarParts.DRILL_HEAD;
    private static final String FULL_STAR = "★";
    private static final String HOLLOW_STAR = "☆";

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Objects.requireNonNull(singleplayer.getServer(), "a world is running");
            ClientWait.until(
                    context, "the player's inventory", client -> client.player != null && client.level != null);
            context.runOnClient(client -> {
                CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook());
                CatalogueScreens.resetState();
            });
            UUID player = context.computeOnClient(
                    client -> ClientSession.player(client).getUUID());
            // A star left in the shared file by an aborted run is not this test's: take every one off through the real
            // API.
            unstarAll(context, player);
            try {
                CatalogueScene.openInventory(context);
                context.waitTicks(2); // tick-wait: the catalogue lists the fixtures it now knows
                theEmptyRowShowsTheHint(context);
                theKeyAndTheStarToggleAndPersist(context, player);
            } finally {
                unstarAll(context, player);
            }
            context.setScreen(() -> null);
        }
    }

    /** Takes every star of {@code player} off, on the client thread, where the shared file is first opened. */
    private static void unstarAll(ClientGameTestContext context, UUID player) {
        context.runOnClient(client -> {
            for (Item left : CatalogueFavourites.shared().items(player)) {
                CatalogueFavourites.shared().toggle(player, left);
            }
        });
    }

    private static Path favouritesFile(ClientGameTestContext context) {
        return context.computeOnClient(client ->
                client.gameDirectory.toPath().resolve("config").resolve(DeepCharter.MOD_ID + "-parts-catalogue.json"));
    }

    /** The favourites of {@code player} as the file on disk holds them, read by a reader of its own. */
    private static List<Item> onDisk(ClientGameTestContext context, UUID player) {
        return CatalogueFavourites.in(favouritesFile(context)).items(player);
    }

    private static List<Drawn> drawn(ClientGameTestContext context, Screen screen) {
        return context.computeOnClient(client -> {
            var window = client.getWindow();
            return DrawnText.of(screen, (int) client.mouseHandler.getScaledXPos(window), (int)
                    client.mouseHandler.getScaledYPos(window));
        });
    }

    private static boolean inside(Drawn text, Rect rect) {
        return text.box().x() >= rect.x()
                && text.box().right() <= rect.right()
                && text.box().y() >= rect.y()
                && text.box().bottom() <= rect.bottom();
    }

    /** The strings drawn inside {@code rect}, top to bottom, joined by a space. */
    private static String textIn(List<Drawn> drawn, Rect rect) {
        return drawn.stream()
                .filter(text -> inside(text, rect))
                .sorted((a, b) -> Integer.compare(a.box().y(), b.box().y()))
                .map(Drawn::text)
                .collect(Collectors.joining(" "));
    }

    private static Screen screen(ClientGameTestContext context) {
        return context.computeOnClient(client -> Objects.requireNonNull(client.gui.screen()));
    }

    /** The favourites row's cells, under its label. */
    private static Rect row(CatalogueLayout layout) {
        CatalogueLayout.Shelf shelf = layout.shelf();
        return new Rect(
                shelf.favourites().x(),
                shelf.favouritesY(),
                shelf.favourites().width(),
                shelf.favourites().bottom() - shelf.favouritesY());
    }

    private static void theEmptyRowShowsTheHint(ClientGameTestContext context) {
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.parkCursor(context, layout);
        List<Drawn> drawn = drawn(context, screen(context));
        require(
                drawn.stream().anyMatch(text -> text.text().equals("FAVOURITES")),
                "the favourites row keeps its label with none starred, drew "
                        + drawn.stream().map(Drawn::text).toList());
        String key = context.computeOnClient(client ->
                CatalogueKeys.FAVOURITE.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT));
        String hint = context.computeOnClient(
                client -> Component.translatable("deepcharter.catalogue.favourites.hint", key, HOLLOW_STAR)
                        .getString());
        String shown = textIn(drawn, row(layout));
        require(shown.equals(hint), "the empty row says how to star an item, '" + hint + "', drew '" + shown + "'");
        require(
                shown.contains(key) && shown.contains(HOLLOW_STAR),
                "the hint names the key bound now and the star, drew '" + shown + "'");
    }

    private static void theKeyAndTheStarToggleAndPersist(ClientGameTestContext context, UUID player) {
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.hover(context, CatalogueScene.gridCell(layout, CatalogueScene.indexOf(context, panel, DRILL)));
        RealInput.key(context, InputConstants.KEY_A, 0);
        require(onDisk(context, player).equals(List.of(DRILL)), "A on a hovered cell stars it, on disk at once");
        CatalogueScene.parkCursor(context, layout);
        require(
                textIn(drawn(context, screen(context)), row(layout)).isEmpty(),
                "with a favourite the hint gives way to its cell");

        // The recipe console of the drill head: its star is full while the item is a favourite.
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
        context.waitTicks(1);
        Rect star = layout.card().star();
        require(
                textIn(drawn(context, screen(context)), star).equals(FULL_STAR),
                "a favourite's recipe console shows a full star");

        CatalogueScene.hover(context, star);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(onDisk(context, player).isEmpty(), "a click on the star takes the star off, on disk at once");
        require(textIn(drawn(context, screen(context)), star).equals(HOLLOW_STAR), "and the star is drawn hollow");
        require(
                context.computeOnClient(client -> panel.state().lookup()) != null,
                "the click on the star leaves the recipe shown");

        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(onDisk(context, player).equals(List.of(DRILL)), "a second click stars it again, on disk");
        require(textIn(drawn(context, screen(context)), star).equals(FULL_STAR), "and the star is full again");

        int cell = CatalogueLook.current().slotSize();
        Rect first = new Rect(layout.shelf().left(), layout.shelf().favouritesY(), cell, cell);
        CatalogueScene.hover(context, first);
        RealInput.key(context, InputConstants.KEY_A, 0);
        require(onDisk(context, player).isEmpty(), "A on the favourite's own cell takes the star off");
    }
}
