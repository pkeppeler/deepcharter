package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * Evidence scenario "stalk-collapse" (#855): a player on foot walks up to the foot of the nearest great stalk the world grew (or, if the
 * survey of the colony's surroundings finds none, the nearest column of fungal stalk at least {@value #TALL_ENOUGH} blocks tall), breaks
 * it bare handed and backs away: everything above the break sags and bursts in a spore puff, layer by layer, and the drops scatter. The
 * stalk is the real generated feature, found by scanning the world, and the collapse is the real server logic. The frames run from the
 * break until the last layer has burst, then the player walks up and picks up what fell.
 */
public class StalkCollapseScenario extends EvidenceScenario {
    /** A great stalk is tall; an ordinary column the scenario falls back to must still have something to sag. */
    private static final int TALL_ENOUGH = 4;

    private static final int GREAT_HEIGHT = 10;
    /** The player starts this far south of the foot, so that the whole stalk, cap and all, is in view. */
    private static final int WATCH_DISTANCE = 13;
    /** Backing away from the stalk after the break, for the first part of the collapse: a quarter of a second of walking. */
    private static final int BACK_AWAY_TICKS = 24;
    /** The quiet walk up to the foot, ending a step inside the reach of a bare hand (about 9.5 blocks from a start 13 away). */
    private static final int APPROACH_TICKS = 44;
    /** The height, in blocks above the foot, that the camera looks at: the middle of a great stalk, so that its cap is in view from a distance. */
    private static final int MIDDLE = 9;
    /** The collapse of the tallest great stalk: its sag, a step for each layer and its cap, and a margin. */
    private static final int COLLAPSE_TICKS = 100;

    @Override
    protected String name() {
        return "stalk-collapse";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        GatherWalk.inWorld(context, this::scenes);
    }

    private void scenes(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        BlockPos foot = singleplayer.getServer().computeOnServer(server -> {
            try {
                return GatherWalk.nearest(server, "great_stalk", true, WATCH_DISTANCE, GREAT_HEIGHT);
            } catch (AssertionError noGreatStalk) {
                return GatherWalk.nearest(server, "fungal_stalk", true, WATCH_DISTANCE, TALL_ENOUGH);
            }
        });
        // The recording is the stalk and nothing else: no HUD, hands or item name, and no toast over it.
        EvidencePins.handsHidden = true;
        EvidencePins.itemNameHidden = true;
        try {
            ClientChecks.withHudHidden(context, () -> collapse(context, singleplayer, foot));
        } finally {
            EvidencePins.reset();
        }
    }

    /** The cut: standing, a walk up and the break with no frames, then the sag, the puff and the stalk settled. */
    private void collapse(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos foot) {
        BlockPos middle = foot.above(MIDDLE);
        GatherWalk walk = new GatherWalk(this, context, singleplayer, WATCH_DISTANCE, false);
        walk.stand(foot);
        context.getInput().lookAt(middle);
        context.waitTicks(10); // tick-wait: the turned view renders before the still
        clean(context);
        screenshot(context, "standing");
        frame(context);
        walk.walk(APPROACH_TICKS);
        walk.breakBlock(foot);
        context.getInput().holdKey(options -> options.keyDown);
        for (int tick = 0; tick < COLLAPSE_TICKS; tick += GatherWalk.TICKS_PER_FRAME) {
            if (tick == BACK_AWAY_TICKS) {
                context.getInput().releaseKey(options -> options.keyDown);
            }
            context.getInput().lookAt(middle);
            context.waitTicks(
                    GatherWalk.TICKS_PER_FRAME); // tick-wait: the collapse takes a fixed number of server ticks
            clean(context);
            frame(context);
        }
        context.getInput().releaseKey(options -> options.keyDown);
        screenshot(context, "collapsed");
    }

    /** Clears the toasts ("New Recipe Unlocked") that would cover the frame. */
    private static void clean(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
    }
}
