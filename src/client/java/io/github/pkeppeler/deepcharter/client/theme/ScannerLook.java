package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.theme.ThemeData;
import java.util.EnumMap;
import java.util.Map;

/**
 * How the scanner minimap looks ({@code theme/scanner.json}). Colours are ARGB. The HUD draws a cell as the block's own texture from the block atlas; neither that nor fill() reads
 * world light. The flat colours below are what a cell with no usable sprite falls back to, and the shades, lifts and tints are laid
 * over the texture ({@code #00000000} turns one off).
 *
 * @param cellPixels GUI pixels per cell
 * @param margin GUI pixels between the panel and the screen edge
 * @param titleColor the panel's title
 * @param airColor air, and the panel's background
 * @param rockColor solid rock, when its block has no usable sprite
 * @param oreColor ore, when its block has no usable sprite
 * @param goldOreColor gold ore, when its block has no usable sprite
 * @param lavaColor a cell with lava in the plane, on a scanner that marks lava, when lava has no usable sprite
 * @param lavaNearColor a cell with lava only beside the plane, when lava has no usable sprite
 * @param gasColor a gas pocket, when its block has no usable sprite
 * @param rockShadeColor laid over the texture of every rock cell, so that the flecks of an ore stand out from the grain around it
 * @param oreLiftColor laid over the texture of every ore cell: the brightness lift that keeps ore as easy to spot as its flat colour was
 * @param goldOreLiftColor as {@code oreLiftColor}, for gold ore
 * @param oreOutlineColor a one-pixel frame round every ore cell, drawn over the texture: it is what makes an ore stand out of the rock
 * @param goldOreOutlineColor as {@code oreOutlineColor}, for gold ore
 * @param oreOutlineMinPixels the smallest cell, in screen pixels, that gets the frame; a smaller cell is all frame, so its lift carries the ore alone
 * @param lavaTintColor laid over the texture of a cell with lava in the plane: red, so that lava is not mistaken for the orange flecks of an ore
 * @param lavaNearShadeColor laid over the texture of a cell with lava only beside the plane: dim, so that it recedes behind the lava in the plane
 * @param gasTintColor laid over the texture of a gas pocket, which looks like stone and so needs a colour to read as gas
 * @param podColor the ridden pod
 * @param frameColor the panel's frame
 * @param oreMarkBelowPixels a cell drawn in fewer screen pixels than this cannot show a texture: an ore cell is then one flat mark of its ore's colour, and rock takes {@code smallRockShadeColor}
 * @param smallRockShadeColor laid over rock in place of {@code rockShadeColor} when cells are smaller than {@code oreMarkBelowPixels}, so the marks stand out of it
 * @param oreMarkColors the mark of each of the mod's ores in a small cell: bright, and told apart by hue
 */
public record ScannerLook(
        int cellPixels,
        int margin,
        int titleColor,
        int airColor,
        int rockColor,
        int oreColor,
        int goldOreColor,
        int lavaColor,
        int lavaNearColor,
        int gasColor,
        int rockShadeColor,
        int oreLiftColor,
        int goldOreLiftColor,
        int oreOutlineColor,
        int goldOreOutlineColor,
        int oreOutlineMinPixels,
        int lavaTintColor,
        int lavaNearShadeColor,
        int gasTintColor,
        int podColor,
        int frameColor,
        int oreMarkBelowPixels,
        int smallRockShadeColor,
        Map<OreType, Integer> oreMarkColors) {
    public static ScannerLook current() {
        return UiTheme.current().scanner();
    }

    public static ScannerLook of(ThemeData d) {
        Map<OreType, Integer> marks = new EnumMap<>(OreType.class);
        for (OreType type : OreType.values()) {
            marks.put(type, d.color(type.itemId().getPath() + "OreMarkColor"));
        }
        return new ScannerLook(
                d.integer("cellPixels", 1),
                d.integer("margin", 0),
                d.color("titleColor"),
                d.color("airColor"),
                d.color("rockColor"),
                d.color("oreColor"),
                d.color("goldOreColor"),
                d.color("lavaColor"),
                d.color("lavaNearColor"),
                d.color("gasColor"),
                d.color("rockShadeColor"),
                d.color("oreLiftColor"),
                d.color("goldOreLiftColor"),
                d.color("oreOutlineColor"),
                d.color("goldOreOutlineColor"),
                d.integer("oreOutlineMinPixels", 4),
                d.color("lavaTintColor"),
                d.color("lavaNearShadeColor"),
                d.color("gasTintColor"),
                d.color("podColor"),
                d.color("frameColor"),
                d.integer("oreMarkBelowPixels", 4),
                d.color("smallRockShadeColor"),
                marks);
    }
}
