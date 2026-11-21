package io.github.pkeppeler.deepcharter.test.evidence;

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
public class StalkCollapseScenario extends SalvageGatherScenario {
    /** A great stalk is tall; an ordinary column the scenario falls back to must still have something to sag. */
    private static final int TALL_ENOUGH = 4;

    private static final int GREAT_HEIGHT = 10;
    /** The player starts this far south of the foot, so that the whole stalk is in view as it comes down. */
    private static final int WATCH_DISTANCE = 9;
    /** Backing away from the stalk after the break, for the first part of the collapse: a quarter of a second of walking. */
    private static final int BACK_AWAY_TICKS = 24;
    /** The collapse of the tallest great stalk: its sag, a step for each layer and its cap, and a margin. */
    private static final int COLLAPSE_TICKS = 100;

    @Override
    protected String name() {
        return "stalk-collapse";
    }

    @Override
    protected void scenes(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        BlockPos foot = singleplayer.getServer().computeOnServer(server -> {
            try {
                return nearest(server, "great_stalk", true, WATCH_DISTANCE, GREAT_HEIGHT);
            } catch (AssertionError noGreatStalk) {
                return nearest(server, "fungal_stalk", true, WATCH_DISTANCE, TALL_ENOUGH);
            }
        });
        Run run = new Run(context, singleplayer, WATCH_DISTANCE);
        run.stand(foot);
        screenshot(context, "standing");
        run.walk(WALK_UP_TICKS);
        run.breakBlock(foot);
        BlockPos watched = foot.above(TALL_ENOUGH);
        context.getInput().holdKey(options -> options.keyDown);
        for (int tick = 0; tick < COLLAPSE_TICKS; tick += TICKS_PER_FRAME) {
            if (tick == BACK_AWAY_TICKS) {
                context.getInput().releaseKey(options -> options.keyDown);
            }
            context.getInput().lookAt(watched);
            context.waitTicks(TICKS_PER_FRAME); // tick-wait: the collapse takes a fixed number of server ticks
            frame(context);
        }
        context.getInput().releaseKey(options -> options.keyDown);
        screenshot(context, "collapsed");
        run.walk(PICK_UP_TICKS);
        screenshot(context, "gathered");
    }
}
