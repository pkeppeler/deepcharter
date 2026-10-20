package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the fake light beams look ({@code theme/beam.json}, #248): the headlamp beam of a lit pod and the beam of a colony floodlight. They
 * are visual only: the real light is the ledgered light block (ADR 0024) and a beam adds none. The cone's texture is
 * {@code textures/effect/beam.png}, multiplied by the colour and faded by the style's alpha.
 *
 * @param sides how many flat faces make the cone's round, 3 to 32
 * @param drawDistance blocks from the camera beyond which a beam is not drawn
 * @param headlamp a lit pod's headlamp
 * @param floodlight a colony floodlight
 */
public record BeamLook(int sides, double drawDistance, BeamStyle headlamp, BeamStyle floodlight) {
    public static BeamLook current() {
        return UiTheme.current().beam();
    }

    public static BeamLook of(ThemeData d) {
        return new BeamLook(
                d.integer("sides", 3, 32),
                d.decimal("drawDistance", 8.0, 512.0),
                BeamStyle.of(d, "headlamp"),
                BeamStyle.of(d, "floodlight"));
    }
}
