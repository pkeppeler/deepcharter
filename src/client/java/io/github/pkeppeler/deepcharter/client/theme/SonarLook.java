package io.github.pkeppeler.deepcharter.client.theme;

import io.github.pkeppeler.deepcharter.pod.SonarKind;
import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * How the navigator's sonar scope looks ({@code theme/sonar.json}, #988): a round plan view of the pod's surroundings, the pod at its middle and
 * the way it faces up, drawn at the left of the screen, with the echoes of the last ping on it. Colours are ARGB; lengths are GUI pixels.
 *
 * @param scopePixels the scope's side, odd so the pod has a middle pixel
 * @param margin space between the scope and the screen's left edge
 * @param titleColor the title over the scope
 * @param backgroundColor the scope's face
 * @param frameColor the one-pixel frame round it
 * @param ringColor the range rings and the cross through the pod
 * @param podColor the pod, and its nose
 * @param oreColor an ore echo, a filled square
 * @param gasColor a gas echo, a hollow square
 * @param caveColor an empty cave echo, a small dot
 * @param movedColor a contact that moved, a cross
 * @param chargeColor the bar under the scope while the sonar clears for the next ping
 * @param chargeBackColor the bar's track
 * @param hintColor the line that names the ping key
 * @param minAlpha how faint an echo gets just before it fades, 0 to 255: it dims from opaque to this
 * @param markColor the frame round an echo the navigator marked on the scope, and the corners round the one they aim at
 * @param markPixels the diamond of a mark on the HUD, odd
 * @param markMargin how near the screen's edge a mark that is off screen sits
 */
public record SonarLook(
        int scopePixels,
        int margin,
        int titleColor,
        int backgroundColor,
        int frameColor,
        int ringColor,
        int podColor,
        int oreColor,
        int gasColor,
        int caveColor,
        int movedColor,
        int chargeColor,
        int chargeBackColor,
        int hintColor,
        int minAlpha,
        int markColor,
        int markPixels,
        int markMargin) {
    /** The colour of an echo of {@code kind}. */
    public int colour(SonarKind kind) {
        return switch (kind) {
            case ORE -> oreColor;
            case GAS -> gasColor;
            case CAVE -> caveColor;
            case MOVED -> movedColor;
        };
    }

    public static SonarLook current() {
        return UiTheme.current().sonar();
    }

    public static SonarLook of(ThemeData d) {
        int scopePixels = d.integer("scopePixels", 21, 201);
        if (scopePixels % 2 == 0) {
            throw d.conflict(
                    "the scope has an odd side, so the pod has a middle pixel, got " + scopePixels, "scopePixels");
        }
        int markPixels = d.integer("markPixels", 5, 31);
        if (markPixels % 2 == 0) {
            throw d.conflict("a mark has an odd side, so it has a middle pixel, got " + markPixels, "markPixels");
        }
        return new SonarLook(
                scopePixels,
                d.integer("margin", 0, 100),
                d.color("titleColor"),
                d.color("backgroundColor"),
                d.color("frameColor"),
                d.color("ringColor"),
                d.color("podColor"),
                d.color("oreColor"),
                d.color("gasColor"),
                d.color("caveColor"),
                d.color("movedColor"),
                d.color("chargeColor"),
                d.color("chargeBackColor"),
                d.color("hintColor"),
                d.integer("minAlpha", 0, 255),
                d.color("markColor"),
                markPixels,
                d.integer("markMargin", 0, 100));
    }
}
