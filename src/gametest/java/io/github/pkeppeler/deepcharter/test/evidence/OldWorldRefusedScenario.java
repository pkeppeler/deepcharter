package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.OldWorlds;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Evidence scenario "m3-old-world-refused" for #800: a world with an old world-format stamp is opened from the world list, and the
 * player reads the refusal screen that points to PLAYING.md.
 */
public class OldWorldRefusedScenario extends EvidenceScenario {
    private static final int HOLD_FRAMES = 4;

    @Override
    protected String name() {
        return "m3-old-world-refused";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        OldWorlds.open(context, OldWorlds.make(context));
        context.waitTicks(2); // tick-wait: the screen draws its text on its first frames
        for (int i = 0; i < HOLD_FRAMES; i++) {
            frame(context);
        }
        screenshot(context, "refusal-screen");
        OldWorlds.backToTheTitleScreen(context);
    }
}
