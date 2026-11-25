package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CataloguePanel;
import io.github.pkeppeler.deepcharter.client.theme.CatalogueLook;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.test.support.CatalogueFixtures;
import io.github.pkeppeler.deepcharter.test.support.CatalogueScene;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields;
import io.github.pkeppeler.deepcharter.test.support.DrawnSprites;
import io.github.pkeppeler.deepcharter.test.support.DrawnSprites.Drawn;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #1290: the inventory lists every active effect, Ore-laden among them, with the parts catalogue open at each window
 * size, clear of the slots, the panels and the catalogue's button; the HUD's top-right icons list it with no screen open; and the status
 * draws no swirl particles, because its icon says it.
 */
public class CatalogueEffectsClientTest implements FabricClientGameTest {
    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;
    /** The narrowest GUI the panels draw at. */
    private static final int NARROWEST = 425;
    /** An item icon, and so a slot's hit area. */
    private static final int ICON = 16;
    /** An effect's icon, vanilla's, and the tile it stands on. */
    private static final int EFFECT_ICON = 18;

    private static final int EFFECT_TILE = 32;
    /** The effects the player carries besides Ore-laden. */
    private static final List<String> OTHERS =
            List.of("speed", "regeneration", "haste", "strength", "jump_boost", "night_vision", "resistance");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Objects.requireNonNull(singleplayer.getServer(), "a world is running");
            ClientWait.until(context, "the player", client -> client.player != null && client.level != null);
            context.runOnClient(client ->
                    CatalogueFixtures.fill(ClientSession.player(client).getRecipeBook()));
            singleplayer
                    .getServer()
                    .runOnServer(server -> server.getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .getInventory()
                            .setItem(0, OreRegistry.stack(OreType.EINSTEINIUM)));
            ClientWait.until(
                    context,
                    "the Ore-laden status on the client",
                    client -> ClientSession.player(client).hasEffect(OreRegistry.OVERBURDENED));

            theStatusDrawsNoSwirls(context);
            theHudListsOreLaden(context);
            theInventoryListsItWithTheCatalogueOpen(context);
            for (String other : OTHERS) {
                singleplayer.getServer().runCommand("effect give @p minecraft:" + other + " infinite 0");
            }
            ClientWait.until(
                    context,
                    "every effect on the client",
                    client -> ClientSession.player(client).getActiveEffects().size() == OTHERS.size() + 1);
            theHudListsOreLaden(context);
            theInventoryListsItWithTheCatalogueOpen(context);
            context.setScreen(() -> null);
        }
    }

    private static void theStatusDrawsNoSwirls(ClientGameTestContext context) {
        MobEffectInstance shown =
                context.computeOnClient(client -> ClientSession.player(client).getEffect(OreRegistry.OVERBURDENED));
        Objects.requireNonNull(shown, "the player carries the status");
        require(!shown.isVisible(), "the status draws no swirl particles: its icon says it, got " + shown);
        require(shown.showIcon(), "and it still shows its icon, got " + shown);
    }

    // ---------------------------------------------------------------- the HUD

    private static void theHudListsOreLaden(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.waitTicks(2);
        List<Drawn> drawn = context.computeOnClient(client ->
                DrawnSprites.of(graphics -> client.gui.hud.extractRenderState(graphics, client.getDeltaTracker())));
        for (MobEffectInstance effect : active(context)) {
            Identifier sprite = Hud.getMobEffectSprite(effect.getEffect());
            List<Drawn> icons =
                    drawn.stream().filter(d -> d.sprite().equals(sprite)).toList();
            require(
                    !icons.isEmpty(),
                    "the HUD's top-right list draws the icon of " + name(effect) + " (" + effect + ", icon "
                            + effect.showIcon() + "), drew "
                            + drawn.stream()
                                    .filter(d -> !d.sprite().getPath().startsWith("hud/heart")
                                            && !d.sprite().getPath().startsWith("hud/food"))
                                    .toList());
        }
    }

    // ---------------------------------------------------------------- the inventory

    private static void theInventoryListsItWithTheCatalogueOpen(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        AbstractContainerScreen<?> screen =
                context.computeOnClient(client -> (AbstractContainerScreen<?>) shown(client));
        try {
            int shortest = shortest();
            for (int height : new int[] {shortest, HEIGHT, 300}) {
                for (int width : new int[] {NARROWEST, WIDTH, 600, 854}) {
                    resize(context, screen, width, height);
                    requireEveryEffectClear(context, screen, "the inventory at " + width + " by " + height);
                }
            }
            // Panels put away by the button's own switch: vanilla's list beside the image, with nothing of ours.
            context.runOnClient(
                    client -> CatalogueScene.panelNow(client).state().toggleHidden());
            resize(context, screen, WIDTH, HEIGHT);
            requireEveryEffectClear(context, screen, "the inventory with the panels put away");
            context.runOnClient(
                    client -> CatalogueScene.panelNow(client).state().toggleHidden());
            // The narrow fallback: no panels, vanilla's recipe book and effect list as ever.
            resize(context, screen, NARROWEST - 1, HEIGHT);
            requireEveryEffectClear(context, screen, "the narrow inventory with no panels");
            resize(context, screen, WIDTH, shortest - 1);
            requireEveryEffectClear(context, screen, "the short inventory with no panels");
        } finally {
            resize(context, screen, WIDTH, HEIGHT);
        }
    }

    /** The shortest GUI the panels draw at: level with the inventory, with a row of effects above it. */
    private static int shortest() {
        CatalogueLook look = CatalogueLook.current();
        return look.consoleHeight() + 2 * (look.screenEdge() + CatalogueLayout.EFFECTS_GAP + EFFECT_TILE);
    }

    private static void resize(ClientGameTestContext context, Screen screen, int width, int height) {
        context.runOnClient(client -> screen.resize(width, height));
        context.waitTicks(2);
    }

    private static void requireEveryEffectClear(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, String at) {
        List<MobEffectInstance> effects = active(context);
        context.runOnClient(client -> {
            CataloguePanel panel = CatalogueScene.panelNow(client);
            CatalogueLayout layout = panel.panelsShown() ? panel.layout() : null;
            List<Drawn> drawn = DrawnSprites.of(screen);
            List<Box> blocked = blocked(screen, layout);
            for (MobEffectInstance effect : effects) {
                Identifier sprite = Hud.getMobEffectSprite(effect.getEffect());
                List<Box> tiles = new ArrayList<>();
                for (Drawn icon : drawn) {
                    if (icon.sprite().equals(sprite) && icon.box().width() == EFFECT_ICON) {
                        Box tile = new Box(icon.box().x() - 7, icon.box().y() - 7, EFFECT_TILE, EFFECT_TILE);
                        if (isClear(tile, blocked, screen)) {
                            tiles.add(tile);
                        }
                    }
                }
                require(
                        !tiles.isEmpty(),
                        at + " draws the icon of " + name(effect)
                                + " on screen and clear of the slots, the panels and the button, "
                                + "drew "
                                + drawn.stream()
                                        .filter(d -> d.sprite().equals(sprite))
                                        .toList()
                                + " against " + blocked);
            }
        });
    }

    /** What an effect's tile must stay off: the inventory image, the panels, the slots, and the catalogue's button. */
    private static List<Box> blocked(AbstractContainerScreen<?> screen, @Nullable CatalogueLayout layout) {
        List<Box> blocked = new ArrayList<>();
        int left = ContainerScreenFields.intField(screen, "leftPos");
        int top = ContainerScreenFields.intField(screen, "topPos");
        blocked.add(new Box(left, top, CatalogueLayout.IMAGE_WIDTH, CatalogueLayout.IMAGE_HEIGHT));
        for (Slot slot : screen.getMenu().slots) {
            blocked.add(new Box(left + slot.x, top + slot.y, ICON, ICON));
        }
        for (AbstractWidget widget : Screens.getWidgets(screen)) {
            if (widget.visible) {
                blocked.add(Box.of(widget));
            }
        }
        if (layout != null) {
            blocked.add(box(layout.catalogue()));
            blocked.add(box(layout.recipe()));
        }
        return blocked;
    }

    private static Box box(CatalogueLayout.Rect rect) {
        return new Box(rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static boolean isClear(Box tile, List<Box> blocked, Screen screen) {
        if (tile.x() < 0 || tile.y() < 0 || tile.right() > screen.width || tile.bottom() > screen.height) {
            return false;
        }
        return blocked.stream().noneMatch(other -> overlap(tile, other));
    }

    private static boolean overlap(Box a, Box b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    private static List<MobEffectInstance> active(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> List.copyOf(ClientSession.player(client).getActiveEffects()));
    }

    private static String name(MobEffectInstance effect) {
        return effect.getEffect().value().getDisplayName().getString();
    }

    private static Screen shown(Minecraft client) {
        return Objects.requireNonNull(client.gui.screen(), "a screen is open");
    }
}
