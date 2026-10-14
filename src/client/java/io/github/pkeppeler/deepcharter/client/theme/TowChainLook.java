package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the tow line looks ({@code theme/tow.json}): a row of links drawn along the line between the two hitch points, from the texture
 * {@code textures/item/tow_chain.png}. Lengths are blocks. The texture is two cells side by side, each half the width and the whole height:
 * the left one is a link seen face on, the right one the same link seen from its edge. Links alternate, so the chain reads from any side.
 *
 * @param linkLength blocks along the line that one link takes up
 * @param linkWidth blocks across one link
 * @param linkOverlap how much longer a link is drawn than {@code linkLength}, so neighbours overlap like links of a chain; 1 is no overlap
 * @param tint the colour the texture is multiplied by; its alpha is ignored
 * @param minBlockLight the least block light, 0 to 15, a link is lit at: the chain reads in a dark layer without glowing
 */
public record TowChainLook(double linkLength, double linkWidth, double linkOverlap, int tint, int minBlockLight) {
    public static TowChainLook current() {
        return UiTheme.current().tow();
    }

    public static TowChainLook of(ThemeData d) {
        return new TowChainLook(
                d.decimal("linkLength", 0.05, 1.0),
                d.decimal("linkWidth", 0.02, 0.5),
                d.decimal("linkOverlap", 1.0, 2.0),
                d.color("tint") | 0xFF000000,
                d.integer("minBlockLight", 0, 15));
    }
}
