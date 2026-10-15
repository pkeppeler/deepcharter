package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * One kind of fake light beam ({@code theme/beam.json}, keys named with the kind as a prefix): a cone of translucent light from a lamp
 * that widens and fades along its length, with a brighter narrow core inside it. Lengths and radii are blocks.
 *
 * @param color the beam's colour; its alpha is ignored
 * @param length how far the beam reaches in open air (a pod's is scaled by its lights part's level out of 15)
 * @param startRadius the cone's radius at the lamp
 * @param endRadius the cone's radius at the end of its full length
 * @param alpha how opaque the outer cone is at the lamp, 0 to 1; it fades to nothing at the full length
 * @param coreScale the core cone's radius as a share of the outer cone's
 * @param coreAlpha how opaque the core cone is at the lamp, 0 to 1
 * @param pitch degrees the beam points below the level, 0 to 90
 * @param forward blocks from the lamp's centre (the middle of the pod, or of the floodlight's block) along its facing, to where the beam starts
 * @param height blocks above the lamp's feet that the beam starts
 */
public record BeamStyle(
        int color,
        double length,
        double startRadius,
        double endRadius,
        double alpha,
        double coreScale,
        double coreAlpha,
        double pitch,
        double forward,
        double height) {
    static BeamStyle of(ThemeData d, String kind) {
        return new BeamStyle(
                d.color(kind + "Color") | 0xFF000000,
                d.decimal(kind + "Length", 1.0, 128.0),
                d.decimal(kind + "StartRadius", 0.01, 4.0),
                d.decimal(kind + "EndRadius", 0.01, 16.0),
                d.decimal(kind + "Alpha", 0.0, 1.0),
                d.decimal(kind + "CoreScale", 0.05, 1.0),
                d.decimal(kind + "CoreAlpha", 0.0, 1.0),
                d.decimal(kind + "Pitch", 0.0, 90.0),
                d.decimal(kind + "Forward", -4.0, 4.0),
                d.decimal(kind + "Height", 0.0, 8.0));
    }
}
