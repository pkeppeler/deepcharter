package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueEffects;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.EffectRow;
import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
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
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #1290: the inventory lists every active effect, Ore/Fuel among them, with the parts catalogue
 * open at each window size and height, clear of the slots, the panels and the catalogue's button; the row's tooltip
 * names the effect, its level and the time left; a crowded row falls back to bare icons that stay in order inside it;
 * vanilla's own list is not drawn under the panels (and is when they are away); the crafting table keeps its old
 * minimum height; the HUD's top-right icons list Ore/Fuel with no screen open; and the status draws no swirl
 * particles, because its icon says it.
 */
public class CatalogueEffectsClientTest implements FabricClientGameTest {
    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;
    /** The narrowest GUI the panels draw at. */
    private static final int NARROWEST = 425;
    /** An item icon, and so a slot's hit area. */
    private static final int ICON = 16;
    /** An effect's icon, vanilla's, and the tile it stands on. */
    private static final int EFFECT_ICON = CatalogueLayout.EFFECT_ICON;

    private static final int EFFECT_TILE = CatalogueLayout.EFFECT_TILE;
    /** The effects the player carries besides Ore/Fuel: the first set. */
    private static final List<String> OTHERS =
            List.of("speed", "regeneration", "haste", "strength", "jump_boost", "night_vision", "hero_of_the_village");
    /** Twelve more, so the player carries twenty: more than a row of tiles can show. */
    private static final List<String> MORE = List.of(
            "slowness",
            "mining_fatigue",
            "nausea",
            "water_breathing",
            "invisibility",
            "blindness",
            "hunger",
            "weakness",
            "glowing",
            "health_boost",
            "absorption",
            "resistance");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        theRowIsCentredInsideItAndInOrder();
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
                    "the Ore/Fuel status on the client",
                    client -> ClientSession.player(client).hasEffect(OreRegistry.OVERBURDENED));

            theStatusDrawsNoSwirls(context);
            theHudListsOreLaden(context);
            theInventoryListsItWithTheCatalogueOpen(context);
            theTooltipNamesTheEffect(context);
            for (String other : OTHERS) {
                singleplayer.getServer().runCommand("effect give @p minecraft:" + other + " infinite 0");
            }
            ClientWait.until(
                    context,
                    "every effect on the client",
                    client -> ClientSession.player(client).getActiveEffects().size() == OTHERS.size() + 1);
            theHudListsOreLaden(context);
            theInventoryListsItWithTheCatalogueOpen(context);
            theCoveredListIsNotDrawnOrHoverable(context);
            theCraftingTableKeepsItsOldMinimum(context);
            for (String more : MORE) {
                singleplayer.getServer().runCommand("effect give @p minecraft:" + more + " infinite 0");
            }
            ClientWait.until(
                    context,
                    "twenty effects on the client",
                    client ->
                            ClientSession.player(client).getActiveEffects().size() == OTHERS.size() + MORE.size() + 1);
            aCrowdedRowFallsBackToIconsInOrder(context);
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

    // ---------------------------------------------------------------- the row, on its own

    private static void theRowIsCentredInsideItAndInOrder() {
        Rect tiles = new Rect(1, 10, 423, EFFECT_TILE);
        Rect icons = new Rect(1, 10, 423, EFFECT_ICON);
        int centre = 212;
        List<Rect> one = CatalogueEffects.boxes(tiles, centre, 1);
        require(
                one.size() == 1 && one.getFirst().x() + EFFECT_TILE / 2 == centre,
                "one effect is a tile centred on the image, got " + one);
        List<Rect> five = CatalogueEffects.boxes(tiles, centre, 5);
        require(
                five.stream().allMatch(box -> box.width() == EFFECT_TILE)
                        && five.getFirst().x()
                                        + (five.getLast().right()
                                                        - five.getFirst().x())
                                                / 2
                                == centre,
                "five tiles stand centred on the image, got " + five);
        for (Rect row : List.of(tiles, icons)) {
            for (int count : new int[] {1, 2, 5, 12, 20, 30, 60}) {
                List<Rect> boxes = CatalogueEffects.boxes(row, centre, count);
                require(boxes.size() == count, "a box for each of " + count + " effects, got " + boxes.size());
                int previous = Integer.MIN_VALUE;
                for (Rect box : boxes) {
                    require(
                            box.x() >= row.x()
                                    && box.right() <= row.right()
                                    && box.y() >= row.y()
                                    && box.bottom() <= row.bottom(),
                            count + " effects in " + row + " leave it: " + box);
                    require(box.x() >= previous, count + " effects are out of order in " + row + ": " + boxes);
                    previous = box.x();
                }
            }
        }
        require(
                CatalogueEffects.boxes(tiles, centre, 20).stream().allMatch(box -> box.width() == EFFECT_ICON),
                "twenty effects are too many for tiles in a row 423 wide, so they are bare icons");
        List<Rect> twenty = CatalogueEffects.boxes(tiles, centre, 20);
        for (int i = 1; i < twenty.size(); i++) {
            require(
                    twenty.get(i).x() - twenty.get(i - 1).x() >= EFFECT_ICON,
                    "the icons of twenty effects do not cover each other, got " + twenty);
        }
        require(
                CatalogueEffects.boxes(icons, centre, 3).stream().allMatch(box -> box.width() == EFFECT_ICON),
                "a row as tall as an icon holds icons");
    }

    // ---------------------------------------------------------------- the HUD

    private static void theHudListsOreLaden(ClientGameTestContext context) {
        context.setScreen(() -> null);
        context.waitTicks(2);
        List<Drawn> drawn = context.computeOnClient(client ->
                DrawnSprites.of(graphics -> client.gui.hud.extractRenderState(graphics, client.getDeltaTracker())));
        for (MobEffectInstance effect : active(context)) {
            Identifier sprite = Hud.getMobEffectSprite(effect.getEffect());
            require(
                    drawn.stream().anyMatch(d -> d.sprite().equals(sprite)),
                    "the HUD's top-right list draws the icon of " + name(effect) + " (" + effect + ")");
        }
    }

    // ---------------------------------------------------------------- the inventory

    private static void theInventoryListsItWithTheCatalogueOpen(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        AbstractContainerScreen<?> screen =
                context.computeOnClient(client -> (AbstractContainerScreen<?>) shown(client));
        CatalogueLook look = CatalogueLook.current();
        int icons = CatalogueLayout.shortest(look, EffectRow.ICONS);
        int tiles = CatalogueLayout.shortest(look, EffectRow.TILES);
        try {
            // Both tiers' edges, and a window each side of them.
            for (int height : new int[] {icons, icons + 4, tiles - 1, tiles, HEIGHT, 300}) {
                for (int width : new int[] {NARROWEST, WIDTH, 600, 854}) {
                    resize(context, screen, width, height);
                    requireEveryEffectClear(
                            context,
                            screen,
                            "the inventory at " + width + " by " + height,
                            height >= tiles ? EffectRow.TILES : EffectRow.ICONS);
                }
            }
            // The middle tier keeps the panels, and a window under it gives them up.
            resize(context, screen, WIDTH, icons - 1);
            requireEveryEffectClear(context, screen, "the short inventory with no panels", null);
            // Panels put away by the button's own switch: vanilla's list beside the image, with nothing of ours.
            context.runOnClient(
                    client -> CatalogueScene.panelNow(client).state().toggleHidden());
            resize(context, screen, WIDTH, HEIGHT);
            requireEveryEffectClear(context, screen, "the inventory with the panels put away", null);
            context.runOnClient(
                    client -> CatalogueScene.panelNow(client).state().toggleHidden());
            // The narrow fallback: no panels, vanilla's recipe book and effect list as ever.
            resize(context, screen, NARROWEST - 1, HEIGHT);
            requireEveryEffectClear(context, screen, "the narrow inventory with no panels", null);
        } finally {
            resize(context, screen, WIDTH, HEIGHT);
        }
    }

    private static void resize(ClientGameTestContext context, Screen screen, int width, int height) {
        context.runOnClient(client -> screen.resize(width, height));
        context.waitTicks(2);
    }

    /**
     * Every active effect is drawn once on screen, clear of what it must stay off. With the panels shown ({@code row}
     * not null) it is inside the row over them, as tiles or as bare icons by {@code row}; with none, it is vanilla's
     * list beside the image.
     */
    private static void requireEveryEffectClear(
            ClientGameTestContext context, AbstractContainerScreen<?> screen, String at, @Nullable EffectRow row) {
        List<MobEffectInstance> effects = active(context);
        context.runOnClient(client -> {
            CataloguePanel panel = CatalogueScene.panelNow(client);
            CatalogueLayout layout = panel.panelsShown() ? panel.layout() : null;
            require((layout != null) == (row != null), at + (layout != null ? " shows" : " hides") + " the panels");
            List<Drawn> drawn = DrawnSprites.of(screen);
            List<Box> blocked = blocked(screen, layout);
            Box effectsRow = layout == null || layout.effects() == null ? null : box(layout.effects());
            for (MobEffectInstance effect : effects) {
                Identifier sprite = Hud.getMobEffectSprite(effect.getEffect());
                List<Box> shown = new ArrayList<>();
                for (Drawn icon : drawn) {
                    if (icon.sprite().equals(sprite) && icon.box().width() == EFFECT_ICON) {
                        Box tile = new Box(icon.box().x() - 7, icon.box().y() - 7, EFFECT_TILE, EFFECT_TILE);
                        boolean onTile = drawn.stream()
                                .anyMatch(d -> isBackground(d) && d.box().equals(tile));
                        Box stands = onTile ? tile : icon.box();
                        if (effectsRow != null) {
                            require(
                                    inside(stands, effectsRow),
                                    at + " draws " + name(effect) + " at " + stands + " outside the row " + effectsRow);
                            require(
                                    onTile == (row == EffectRow.TILES),
                                    at + " draws " + name(effect) + (onTile ? " on a tile" : " as a bare icon")
                                            + ", not as " + row);
                        }
                        if (isClear(stands, blocked, screen)) {
                            shown.add(stands);
                        }
                    }
                }
                require(
                        shown.size() == 1,
                        at + " draws the icon of " + name(effect)
                                + " once, on screen and clear of the slots, the panels and the" + " button; drew it "
                                + shown + " against " + blocked);
            }
        });
    }

    /** What an effect's box must stay off: the inventory image, the panels, the slots, and the catalogue's button. */
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

    private static Box box(Rect rect) {
        return new Box(rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static boolean isBackground(Drawn drawn) {
        return drawn.sprite().getPath().endsWith("effect_background")
                || drawn.sprite().getPath().endsWith("effect_background_ambient");
    }

    private static boolean inside(Box inner, Box outer) {
        return inner.x() >= outer.x()
                && inner.y() >= outer.y()
                && inner.right() <= outer.right()
                && inner.bottom() <= outer.bottom();
    }

    private static boolean isClear(Box box, List<Box> blocked, Screen screen) {
        if (box.x() < 0 || box.y() < 0 || box.right() > screen.width || box.bottom() > screen.height) {
            return false;
        }
        return blocked.stream().noneMatch(other -> overlap(box, other));
    }

    private static boolean overlap(Box a, Box b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    // ---------------------------------------------------------------- the tooltip

    private static void theTooltipNamesTheEffect(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        AbstractContainerScreen<?> screen =
                context.computeOnClient(client -> (AbstractContainerScreen<?>) shown(client));
        resize(context, screen, WIDTH, HEIGHT);
        context.runOnClient(client -> {
            MobEffectInstance effect = Objects.requireNonNull(
                    ClientSession.player(client).getEffect(OreRegistry.OVERBURDENED), "Ore/Fuel is active");
            Identifier sprite = Hud.getMobEffectSprite(OreRegistry.OVERBURDENED);
            CatalogueLayout layout =
                    Objects.requireNonNull(CatalogueScene.panelNow(client).layout(), "the panels are laid out");
            Box row = box(Objects.requireNonNull(layout.effects(), "the inventory has a row of effects"));
            Box icon = DrawnSprites.of(screen).stream()
                    .filter(d -> d.sprite().equals(sprite))
                    .map(Drawn::box)
                    .filter(box -> inside(box, row))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the row draws the Ore/Fuel icon"));
            DrawnSprites.Frame away = DrawnSprites.of(screen, 0, 0);
            require(away.tooltips().isEmpty(), "no effect tooltip with the mouse away, got " + away.tooltips());
            DrawnSprites.Frame over =
                    DrawnSprites.of(screen, icon.x() + icon.width() / 2, icon.y() + icon.height() / 2);
            float rate =
                    client.level == null ? 20f : client.level.tickRateManager().tickrate();
            String name = OreRegistry.OVERBURDENED.value().getDisplayName().getString();
            require(
                    over.tooltips()
                            .equals(List.of(List.of(
                                    name + " IV",
                                    MobEffectUtil.formatDuration(effect, 1f, rate)
                                            .getString()))),
                    "hovering the icon shows the name, level and time left of " + name + ", got " + over.tooltips());
            require(
                    Component.translatable("enchantment.level." + (effect.getAmplifier() + 1))
                            .getString()
                            .equals("IV"),
                    "the level is in numerals");
        });
    }

    // ---------------------------------------------------------------- vanilla's list under the panels (ADR 0094)

    private static void theCoveredListIsNotDrawnOrHoverable(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        AbstractContainerScreen<?> screen =
                context.computeOnClient(client -> (AbstractContainerScreen<?>) shown(client));
        resize(context, screen, WIDTH, HEIGHT);
        try {
            context.runOnClient(client -> {
                CatalogueLayout layout =
                        Objects.requireNonNull(CatalogueScene.panelNow(client).layout(), "the panels are laid out");
                Box row = box(Objects.requireNonNull(layout.effects(), "the inventory has a row of effects"));
                List<Drawn> drawn = DrawnSprites.of(screen);
                require(
                        drawn.stream()
                                .filter(CatalogueEffectsClientTest::isBackground)
                                .allMatch(d -> inside(d.box(), row)),
                        "vanilla's effect tiles are not drawn under the panels, drew "
                                + drawn.stream()
                                        .filter(CatalogueEffectsClientTest::isBackground)
                                        .filter(d -> !inside(d.box(), row))
                                        .toList());
                // Where vanilla's list would be, a clipped name (Hero of the Village) would set its hover tooltip.
                int left = ContainerScreenFields.intField(screen, "leftPos") + CatalogueLayout.IMAGE_WIDTH + 4;
                int top = ContainerScreenFields.intField(screen, "topPos");
                for (int y = top; y < top + CatalogueLayout.IMAGE_HEIGHT; y += 3) {
                    DrawnSprites.Frame frame = DrawnSprites.of(screen, left, y);
                    require(
                            frame.tooltips().isEmpty(),
                            "no vanilla effect tooltip under the panels at " + left + "," + y + ", got "
                                    + frame.tooltips());
                }
            });
            // Put away, vanilla's list is the one drawn, and its clipped name is hoverable.
            context.runOnClient(
                    client -> CatalogueScene.panelNow(client).state().toggleHidden());
            context.waitTicks(2);
            context.runOnClient(client -> {
                int left = ContainerScreenFields.intField(screen, "leftPos") + CatalogueLayout.IMAGE_WIDTH + 4;
                int top = ContainerScreenFields.intField(screen, "topPos");
                require(
                        DrawnSprites.of(screen).stream().anyMatch(CatalogueEffectsClientTest::isBackground),
                        "vanilla's effect tiles are drawn again with the panels put away");
                boolean tooltip = false;
                for (int y = top; y < top + CatalogueLayout.IMAGE_HEIGHT && !tooltip; y += 3) {
                    tooltip = !DrawnSprites.of(screen, left, y).tooltips().isEmpty();
                }
                require(tooltip, "and so is its hover tooltip, on a name too long for the list");
            });
        } finally {
            context.runOnClient(client -> {
                if (CatalogueScene.panelNow(client).state().hidden()) {
                    CatalogueScene.panelNow(client).state().toggleHidden();
                }
            });
        }
    }

    // ---------------------------------------------------------------- the crafting table

    private static void theCraftingTableKeepsItsOldMinimum(ClientGameTestContext context) {
        context.setScreen(() -> new CraftingScreen(
                new CraftingMenu(
                        0, ClientSession.player(Minecraft.getInstance()).getInventory()),
                ClientSession.player(Minecraft.getInstance()).getInventory(),
                Component.translatable("container.crafting")));
        ClientWait.screen(context, CraftingScreen.class);
        AbstractContainerScreen<?> screen =
                context.computeOnClient(client -> (AbstractContainerScreen<?>) shown(client));
        CatalogueLook look = CatalogueLook.current();
        int plain = CatalogueLayout.shortest(look, EffectRow.NONE);
        require(
                plain < CatalogueLayout.shortest(look, EffectRow.ICONS),
                "the table's minimum is under the inventory's");
        try {
            resize(context, screen, WIDTH, plain);
            context.runOnClient(client -> {
                CataloguePanel panel = CatalogueScene.panelNow(client);
                CatalogueLayout layout =
                        Objects.requireNonNull(panel.layout(), "the table has the panels at " + plain + " tall");
                require(layout.effects() == null, "the crafting table has no row of effects");
                require(
                        DrawnSprites.of(screen).stream()
                                .noneMatch(d -> d.box().width() == EFFECT_ICON
                                        && d.sprite().getPath().startsWith("mob_effect/")),
                        "and draws no effect icon, as in vanilla");
            });
            resize(context, screen, WIDTH, plain - 1);
            require(
                    context.computeOnClient(
                                    client -> CatalogueScene.panelNow(client).layout())
                            == null,
                    "a window under the table's own minimum hides the panels");
        } finally {
            context.setScreen(() -> null);
        }
    }

    // ---------------------------------------------------------------- a crowded row

    private static void aCrowdedRowFallsBackToIconsInOrder(ClientGameTestContext context) {
        CatalogueScene.openInventory(context);
        AbstractContainerScreen<?> screen =
                context.computeOnClient(client -> (AbstractContainerScreen<?>) shown(client));
        try {
            resize(context, screen, NARROWEST, HEIGHT);
            List<MobEffectInstance> effects = active(context);
            require(effects.size() == 20, "the player carries twenty effects, carries " + effects.size());
            context.runOnClient(client -> {
                CatalogueLayout layout =
                        Objects.requireNonNull(CatalogueScene.panelNow(client).layout(), "the panels are laid out");
                Box row = box(Objects.requireNonNull(layout.effects(), "the inventory has a row of effects"));
                List<Drawn> drawn = DrawnSprites.of(screen);
                require(
                        drawn.stream().noneMatch(d -> isBackground(d) && inside(d.box(), row)),
                        "twenty effects are bare icons: no tile is drawn in the row");
                List<Box> icons = drawn.stream()
                        .filter(d -> d.box().width() == EFFECT_ICON
                                && d.sprite().getPath().startsWith("mob_effect/"))
                        .map(Drawn::box)
                        .toList();
                require(icons.size() == 20, "the row draws twenty icons, drew " + icons.size());
                for (int i = 0; i < icons.size(); i++) {
                    Box icon = icons.get(i);
                    require(inside(icon, row), "icon " + i + " " + icon + " stays inside the row " + row);
                    if (i > 0) {
                        require(
                                icon.x() - icons.get(i - 1).x() >= EFFECT_ICON,
                                "icon " + i + " is in order and clear of the icon before it, " + icons);
                    }
                }
            });
        } finally {
            resize(context, screen, WIDTH, HEIGHT);
        }
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
