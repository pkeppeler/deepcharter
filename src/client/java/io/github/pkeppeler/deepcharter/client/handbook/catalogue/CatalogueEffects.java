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
 * The inventory's active effects, drawn in a row over the catalogue (see {@link CatalogueLayout#effects}) as vanilla's
 * compact list draws them: each is a tile with its icon, and the name, level and time left are its tooltip. A row too
 * short for tiles, or too crowded for them, is bare icons. Vanilla's own list stands right of the inventory image,
 * under the catalogue while the panels are shown, and is not drawn then (ADR 0094).
 */
public final class CatalogueEffects {
    private static final Identifier BACKGROUND =
            Identifier.withDefaultNamespace("container/inventory/effect_background");
    private static final Identifier BACKGROUND_AMBIENT =
            Identifier.withDefaultNamespace("container/inventory/effect_background_ambient");
    /** The icon's inset in its tile, as vanilla draws it. */
    private static final int ICON_INSET = 7;
    /** The pitch of tiles, one pixel between them as vanilla's compact list has; of bare icons, two. */
    private static final int TILE_PITCH = CatalogueLayout.EFFECT_TILE + 1;

    private static final int ICON_PITCH = CatalogueLayout.EFFECT_ICON + 2;
    /**
     * The pitch under which a tile's background would cover the icon of the tile before it, which sits in its first 25
     * pixels.
     */
    private static final int TILE_CLEAR_PITCH = ICON_INSET + CatalogueLayout.EFFECT_ICON;
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
        List<Rect> boxes = boxes(row, centre, effects.size());
        int hovered = -1;
        for (int i = 0; i < effects.size(); i++) {
            Rect box = boxes.get(i);
            MobEffectInstance effect = effects.get(i);
            int iconX = box.x();
            int iconY = box.y();
            if (box.width() == CatalogueLayout.EFFECT_TILE) {
                graphics.blitSprite(
                        RenderPipelines.GUI_TEXTURED,
                        effect.isAmbient() ? BACKGROUND_AMBIENT : BACKGROUND,
                        box.x(),
                        box.y(),
                        box.width(),
                        box.height());
                iconX += ICON_INSET;
                iconY += ICON_INSET;
            }
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED,
                    Hud.getMobEffectSprite(effect.getEffect()),
                    iconX,
                    iconY,
                    CatalogueLayout.EFFECT_ICON,
                    CatalogueLayout.EFFECT_ICON);
            if (box.contains(mouseX, mouseY)) {
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

    /**
     * Where {@code count} effects stand in {@code row}, in order, centred on {@code centre} and kept inside the row: 32
     * px tiles a pitch apart while the row is as tall as one and they stay clear of each other's icons, else bare
     * icons, which in a row too crowded even for them overlap evenly.
     */
    // test-seam: the client test pins the squeeze and the centring on a bare row, with no world or window to set up
    public static List<Rect> boxes(Rect row, int centre, int count) {
        int tile = CatalogueLayout.EFFECT_TILE;
        int icon = CatalogueLayout.EFFECT_ICON;
        boolean tiles =
                row.height() >= tile && (count < 2 || pitch(row.width(), tile, TILE_PITCH, count) >= TILE_CLEAR_PITCH);
        int side = tiles ? tile : icon;
        int pitch = pitch(row.width(), side, tiles ? TILE_PITCH : ICON_PITCH, count);
        int total = (count - 1) * pitch + side;
        int x = Math.clamp(centre - total / 2, row.x(), Math.max(row.x(), row.right() - total));
        int y = row.y() + (row.height() - side) / 2;
        List<Rect> boxes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boxes.add(new Rect(x + i * pitch, y, side, side));
        }
        return boxes;
    }

    /** The pitch of {@code count} boxes {@code side} wide: {@code wanted}, or less where {@code width} is short. */
    private static int pitch(int width, int side, int wanted, int count) {
        return count < 2 ? 0 : Math.min(wanted, (width - side) / (count - 1));
    }

    /** An effect's name, then its level in numerals from the second, as vanilla's list words it. */
    static Component label(MobEffectInstance effect) {
        MutableComponent name = effect.getEffect().value().getDisplayName().copy();
        if (effect.getAmplifier() >= 1 && effect.getAmplifier() <= LAST_NUMERAL) {
            name.append(CommonComponents.SPACE)
                    .append(Component.translatable("enchantment.level." + (effect.getAmplifier() + 1)));
        }
        return name;
    }
}
