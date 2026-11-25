package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

/**
 * Evidence scenario "stalk-collapse" (#855): a player on foot walks up to the foot of the nearest great stalk the world
 * grew (or, if the survey of the colony's surroundings finds none, the nearest column of fungal stalk at least {@value
 * #TALL_ENOUGH} blocks tall), breaks it bare handed and backs away: everything above the break bursts at once in a
 * spore puff, like sugar cane, and the drops scatter. The stalk is the real generated feature, found by scanning the
 * world, and the collapse is the real server logic. The frames run from the break until the puff and the drops have
 * settled; the camera drops from the middle of the stalk to the puff at the foot.
 */
public class StalkCollapseScenario extends EvidenceScenario {
    /** A great stalk is tall; an ordinary column the scenario falls back to must still have something to bring down. */
    private static final int TALL_ENOUGH = 4;

    private static final int GREAT_HEIGHT = 10;
    /** The player starts this far south of the foot, so that the whole stalk, cap and all, is in view. */
    private static final int WATCH_DISTANCE = 13;
    /** Backing away from the stalk after the break, for the first part of the collapse: a quarter of a second of walking. */
    private static final int BACK_AWAY_TICKS = 24;
    /** The quiet walk up to the foot, ending a step inside the reach of a bare hand (about 9.5 blocks from a start 13 away). */
    private static final int APPROACH_TICKS = 44;
    /**
     * The camera drops to this many blocks above the foot, where the puff and the drops are, over {@link
     * #FOLLOW_TICKS}.
     */
    private static final int LOW_LOOK = 1;
    /** The ticks the camera takes to drop from the middle of the stalk down to {@link #LOW_LOOK}. */
    private static final int FOLLOW_TICKS = 12;
    /** From the break: the collapse is instant, so this is the puff and the scattering drops settling, and a margin. */
    private static final int COLLAPSE_TICKS = 45;

    @Override
    protected String name() {
        return "stalk-collapse";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        GatherWalk.inWorld(context, this::scenes);
    }

    private void scenes(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        Column found = singleplayer.getServer().computeOnServer(server -> {
            try {
                return foot(server, "great_stalk", GREAT_HEIGHT);
            } catch (AssertionError noGreatStalk) {
                return foot(server, "fungal_stalk", TALL_ENOUGH);
            }
        });
        BlockPos foot = found.foot();
        int height = found.height();
        // The recording is the stalk and nothing else: no HUD, hands or item name, and no toast over it.
        EvidencePins.handsHidden = true;
        EvidencePins.itemNameHidden = true;
        try {
            ClientChecks.withHudHidden(context, () -> collapse(context, singleplayer, foot, height));
        } finally {
            EvidencePins.reset();
        }
    }

    /** The cut: standing, a walk up and the break with no frames, then the puff and the stalk gone. */
    private void collapse(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos foot, int height) {
        BlockPos middle = foot.above(height / 2);
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
            // The view starts on the middle of the stalk and drops to the puff at its foot.
            int follow = Math.min(tick, FOLLOW_TICKS);
            int aim = height / 2 - (height / 2 - LOW_LOOK) * follow / FOLLOW_TICKS;
            context.getInput().lookAt(foot.above(aim));
            context.waitTicks(
                    GatherWalk
                            .TICKS_PER_FRAME); // tick-wait: the puff and the drops take a fixed number of server ticks
            clean(context);
            frame(context);
        }
        context.getInput().releaseKey(options -> options.keyDown);
        screenshot(context, "collapsed");
    }

    /** A column of stalk: the position of its lowest block and how many blocks stand in it. */
    private record Column(BlockPos foot, int height) {}

    /** The nearest column of the block that a player can walk up to. */
    private static Column foot(MinecraftServer server, String path, int least) {
        BlockPos foot = GatherWalk.nearest(server, path, true, WATCH_DISTANCE, least);
        Block kind = server.overworld().getBlockState(foot).getBlock();
        int height = 1;
        while (server.overworld().getBlockState(foot.above(height)).is(kind)) {
            height++;
        }
        return new Column(foot, height);
    }

    /** Clears the toasts ("New Recipe Unlocked") that would cover the frame. */
    private static void clean(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
    }
}
