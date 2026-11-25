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
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.RecipeConsoleScreen;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DrawnSprites;
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
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client GameTest for #1266 (c): favourites. With none starred the favourites row is still there, with a hint that names the key bound
 * now and shows the recipe console's hollow star. A on a hovered item stars it and a click on the star of the recipe console takes the
 * star off and puts it back; the star sprite is filled for a favourite and hollow for any other item, and each change is in the favourites
 * file at once, read back from disk the way {@link CatalogueFavourites} keeps it.
 */
public class CatalogueFavouritesClientTest implements FabricClientGameTest {
    private static final Item DRILL = HangarParts.DRILL_HEAD;

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
                theStarSpritesResolve(context);
                theEmptyRowShowsTheHint(context);
                theKeyAndTheStarToggleAndPersist(context, player);
                inUsesTheStarIsTheTitledItems(context, player);
                theChestConsolesStarToggles(context, singleplayer, player);
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
        return context.computeOnClient(client -> DrawnText.atCursor(screen));
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

    /** The star sprites drawn with their middle in {@code rect}, each once: a lit star's glow is copies of the same sprite. */
    private static List<Identifier> starsIn(ClientGameTestContext context, Rect rect) {
        return context.computeOnClient(client -> {
            var window = client.getWindow();
            return DrawnSprites.of(
                            Objects.requireNonNull(client.gui.screen()),
                            (int) client.mouseHandler.getScaledXPos(window),
                            (int) client.mouseHandler.getScaledYPos(window))
                    .sprites()
                    .stream()
                    .filter(sprite -> sprite.sprite().equals(CatalogueLook.STAR)
                            || sprite.sprite().equals(CatalogueLook.STAR_HOLLOW))
                    .filter(sprite -> rect.contains(
                            sprite.box().x() + sprite.box().width() / 2,
                            sprite.box().y() + sprite.box().height() / 2))
                    .map(DrawnSprites.Drawn::sprite)
                    .distinct()
                    .toList();
        });
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

    /** Both star sprites are in the GUI atlas, 9 x 9: a missing one would be drawn as the missing texture and still be recorded. */
    private static void theStarSpritesResolve(ClientGameTestContext context) {
        for (Identifier id : List.of(CatalogueLook.STAR, CatalogueLook.STAR_HOLLOW)) {
            context.runOnClient(client -> {
                SpriteContents contents = client.getAtlasManager()
                        .getAtlasOrThrow(AtlasIds.GUI)
                        .getSprite(id)
                        .contents();
                require(
                        !contents.name().equals(MissingTextureAtlasSprite.getLocation()),
                        id + " is in the GUI atlas, not the missing sprite");
                require(
                        contents.width() == 9 && contents.height() == 9,
                        id + " is 9 x 9, got " + contents.width() + " x " + contents.height());
            });
        }
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
        // The star in the hint is a sprite, not a letter: the words are the hint's with the star taken out.
        String words = context.computeOnClient(
                client -> Component.translatable("deepcharter.catalogue.favourites.hint", key, "")
                        .getString()
                        .replaceAll("\\s+", " ")
                        .strip());
        String shown = textIn(drawn, row(layout));
        require(shown.equals(words), "the empty row says how to star an item, '" + words + "', drew '" + shown + "'");
        require(shown.contains(key), "the hint names the key bound now, drew '" + shown + "'");
        List<Identifier> stars = starsIn(context, row(layout));
        require(
                stars.equals(List.of(CatalogueLook.STAR_HOLLOW)),
                "the hint shows the recipe console's hollow star, drew " + stars);
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
        require(starsIn(context, row(layout)).isEmpty(), "and its star with it");

        // The recipe console of the drill head: its star is full while the item is a favourite.
        context.runOnClient(client -> panel.state().look(panel.catalogue(), DRILL, CatalogueState.Mode.RECIPES));
        context.waitTicks(1);
        Rect star = layout.card().star();
        require(
                starsIn(context, star).equals(List.of(CatalogueLook.STAR)),
                "a favourite's recipe console shows a full star, drew " + starsIn(context, star));

        CatalogueScene.hover(context, star);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(onDisk(context, player).isEmpty(), "a click on the star takes the star off, on disk at once");
        require(
                starsIn(context, star).equals(List.of(CatalogueLook.STAR_HOLLOW)),
                "and the star is drawn hollow, drew " + starsIn(context, star));
        require(
                context.computeOnClient(client -> panel.state().lookup()) != null,
                "the click on the star leaves the recipe shown");

        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(onDisk(context, player).equals(List.of(DRILL)), "a second click stars it again, on disk");
        require(
                starsIn(context, star).equals(List.of(CatalogueLook.STAR)),
                "and the star is full again, drew " + starsIn(context, star));

        int cell = CatalogueLook.current().slotSize();
        Rect first = new Rect(layout.shelf().left(), layout.shelf().favouritesY(), cell, cell);
        CatalogueScene.hover(context, first);
        RealInput.key(context, InputConstants.KEY_A, 0);
        require(onDisk(context, player).isEmpty(), "A on the favourite's own cell takes the star off");
    }

    /**
     * In U the console is titled with what the shown recipe makes, not with the item looked up: the star is that item's, filled for it
     * and toggling it.
     */
    private static void inUsesTheStarIsTheTitledItems(ClientGameTestContext context, UUID player) {
        CataloguePanel panel = CatalogueScene.panel(context);
        CatalogueLayout layout = CatalogueScene.layout(context, panel);
        CatalogueScene.parkCursor(context, layout);
        context.runOnClient(
                client -> panel.state().look(panel.catalogue(), Items.IRON_INGOT, CatalogueState.Mode.USES));
        context.waitTicks(1);
        Item titled = context.computeOnClient(
                client -> Objects.requireNonNull(panel.state().shownRecipe(panel.catalogue()), "the iron has a use")
                        .results()
                        .getFirst()
                        .getItem());
        require(titled != Items.IRON_INGOT, "the use shown makes something other than iron, got " + titled);
        Rect star = layout.card().star();
        String title = new ItemStack(titled).getHoverName().getString().toUpperCase(Locale.ROOT);
        List<String> titleLines = context.computeOnClient(client -> panel.titleLines());
        require(String.join(" ", titleLines).equals(title), "the console is titled " + title + ", got " + titleLines);

        // The iron starred: the item named beside the star is not, so the star stays hollow.
        context.runOnClient(client -> CatalogueFavourites.shared().toggle(player, Items.IRON_INGOT));
        try {
            context.waitTicks(1);
            require(
                    starsIn(context, star).equals(List.of(CatalogueLook.STAR_HOLLOW)),
                    "with the iron starred the star of " + title + " is hollow, drew " + starsIn(context, star));
        } finally {
            context.runOnClient(client -> CatalogueFavourites.shared().toggle(player, Items.IRON_INGOT));
        }
        CatalogueScene.hover(context, star);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(
                onDisk(context, player).equals(List.of(titled)),
                "a click on the star stars " + title + ", the item named beside it, got " + onDisk(context, player));
        require(
                starsIn(context, star).equals(List.of(CatalogueLook.STAR)),
                "and the star is full, drew " + starsIn(context, star));
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(onDisk(context, player).isEmpty(), "a second click takes the star off again");
        context.setScreen(() -> null);
    }

    /** The recipe console a chest's R opens has the star too, and a click on it stars the item and takes the star off. */
    private static void theChestConsolesStarToggles(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, UUID player) {
        ContainerScreen chest = CatalogueScene.openChest(context, singleplayer, List.of(new ItemStack(DRILL)));
        CatalogueScene.hover(
                context,
                CatalogueScene.slotCell(
                        context, chest, slot -> !(slot.container instanceof Inventory) && slot.index == 0));
        RealInput.key(context, InputConstants.KEY_R, 0);
        ClientWait.until(
                context, "the chest's recipe console", client -> client.gui.screen() instanceof RecipeConsoleScreen);
        Screen console = screen(context);
        Rect star = CatalogueScene.centredConsole(context, console).card().star();
        require(
                starsIn(context, star).equals(List.of(CatalogueLook.STAR_HOLLOW)),
                "the chest's console shows a hollow star, drew " + starsIn(context, star));
        CatalogueScene.hover(context, star);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(onDisk(context, player).equals(List.of(DRILL)), "a click on its star stars the drill head, on disk");
        require(
                starsIn(context, star).equals(List.of(CatalogueLook.STAR)),
                "and the star is full, drew " + starsIn(context, star));
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        require(onDisk(context, player).isEmpty(), "a second click takes the star off");
        require(screen(context) == console, "the clicks on the star leave the console open");
        context.setScreen(() -> null);
    }
}
