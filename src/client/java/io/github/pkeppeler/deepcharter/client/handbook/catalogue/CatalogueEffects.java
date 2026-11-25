package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import io.github.pkeppeler.deepcharter.client.handbook.catalogue.CatalogueLayout.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;

/**
 * The inventory's active effects, drawn in a row over the catalogue (see {@link CatalogueLayout#effects}) as vanilla's compact list draws
 * them: each is a tile with its icon, and the name, level and time left are its tooltip. Vanilla's own list stands right of the inventory
 * image, which is under the catalogue while the panels are shown, so it is drawn there and nobody sees it.
 */
final class CatalogueEffects {
    private static final Identifier BACKGROUND =
            Identifier.withDefaultNamespace("container/inventory/effect_background");
    private static final Identifier BACKGROUND_AMBIENT =
            Identifier.withDefaultNamespace("container/inventory/effect_background_ambient");
    /** The icon's side and its inset in the tile, as vanilla draws them. */
    private static final int ICON = 18;

    private static final int ICON_INSET = 7;
    /** The pitch of the tiles: one pixel between them, as vanilla's compact list has. */
    private static final int PITCH = CatalogueLayout.EFFECT_TILE + 1;
    /** The highest amplifier whose level has a numeral in the language file, as in vanilla. */
    private static final int LAST_NUMERAL = 9;

    private CatalogueEffects() {}

    /** Draws the player's effects centred on {@code centre} in {@code row}, and the tooltip of the one the mouse is on. */
    static void extract(GuiGraphicsExtractor graphics, Rect row, int centre, int mouseX, int mouseY) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || player.getActiveEffects().isEmpty()) {
            return;
        }
        List<MobEffectInstance> effects = new ArrayList<>(player.getActiveEffects());
        Collections.sort(effects);
        List<Rect> tiles = tiles(row, centre, effects.size());
        int hovered = -1;
        for (int i = 0; i < effects.size(); i++) {
            Rect tile = tiles.get(i);
            MobEffectInstance effect = effects.get(i);
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    effect.isAmbient() ? BACKGROUND_AMBIENT : BACKGROUND,
                    tile.x(),
                    tile.y(),
                    tile.width(),
                    tile.height());
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    Hud.getMobEffectSprite(effect.getEffect()),
                    tile.x() + ICON_INSET,
                    tile.y() + ICON_INSET,
                    ICON,
                    ICON);
            if (tile.contains(mouseX, mouseY)) {
                hovered = i;
            }
        }
        if (hovered >= 0) {
            MobEffectInstance effect = effects.get(hovered);
            float tickRate =
                    client.level == null ? 20f : client.level.tickRateManager().tickrate();
            graphics.setTooltipForNextFrame(
                    client.font,
                    List.of(label(effect), MobEffectUtil.formatDuration(effect, 1f, tickRate)),
                    Optional.empty(),
                    mouseX,
                    mouseY);
        }
    }

    /** The tiles of {@code count} effects: a pitch apart, as close as {@code row} lets them when it is too short for that, centred on {@code centre}. */
    static List<Rect> tiles(Rect row, int centre, int count) {
        int tile = CatalogueLayout.EFFECT_TILE;
        int pitch = count < 2 ? 0 : Math.min(PITCH, (row.width() - tile) / (count - 1));
        int total = (count - 1) * pitch + tile;
        int x = Math.clamp(centre - total / 2, row.x(), Math.max(row.x(), row.right() - total));
        List<Rect> tiles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tiles.add(new Rect(x + i * pitch, row.y(), tile, tile));
        }
        return tiles;
    }

    /** An effect's name, then its level in numerals from the second, as vanilla's list words it. */
    private static Component label(MobEffectInstance effect) {
        MutableComponent name = effect.getEffect().value().getDisplayName().copy();
        if (effect.getAmplifier() >= 1 && effect.getAmplifier() <= LAST_NUMERAL) {
            name.append(CommonComponents.SPACE)
                    .append(Component.translatable("enchantment.level." + (effect.getAmplifier() + 1)));
        }
        return name;
    }
}
