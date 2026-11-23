package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How a layer crossing looks ({@code theme/breach.json}): the HUD shake. All timings are visual and count client ticks from the moment
 * the crossing's payload arrives; 20 ticks are one second.
 *
 * @param jitterTicks ticks the HUD shakes, settling as it goes
 * @param jitterPixels how far the HUD shakes at first, in GUI pixels
 */
public record BreachLook(int jitterTicks, int jitterPixels) {
    public static BreachLook current() {
        return UiTheme.current().breach();
    }

    /** The longest a shake may last, so that a pack cannot shake the HUD for long. */
    static final int MAX_TICKS = 100;

    public static BreachLook of(ThemeData d) {
        return new BreachLook(d.integer("jitterTicks", 1, MAX_TICKS), d.integer("jitterPixels", 0, 32));
    }
}
