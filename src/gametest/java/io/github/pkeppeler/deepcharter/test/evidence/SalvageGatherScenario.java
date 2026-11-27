package io.github.pkeppeler.deepcharter.test.evidence;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;

/**
 * Evidence scenario "salvage-gather" (#245): a new player on foot, bare handed, with the hotbar showing, walks up to a crate the world
 * generated and breaks it, picks up what falls out (wood, scrap, cloth), then does the same to the foot of the nearest fungal stalk
 * (which, breaking, brings the stalk above it down, so it is broken once). Both are the real generated features, found by scanning the
 * world, and the drops are the real loot tables. The two walks are cut.
 */
public class SalvageGatherScenario extends EvidenceScenario {
    /** How far in front of the block the player starts, and how near the walk ends. */
    private static final int START_DISTANCE = 4;
    /** Any height of stalk will do. */
    private static final int ANY_HEIGHT = 1;

    @Override
    protected String name() {
        return "salvage-gather";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        GatherWalk.inWorld(context, this::scenes);
    }

    private void scenes(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        BlockPos crate = singleplayer
                .getServer()
                .computeOnServer(
                        server -> GatherWalk.nearest(server, "salvage_crate", false, START_DISTANCE, ANY_HEIGHT));
        BlockPos stalk = singleplayer
                .getServer()
                .computeOnServer(
                        server -> GatherWalk.nearest(server, "fungal_stalk", true, START_DISTANCE, ANY_HEIGHT));
        GatherWalk walk = new GatherWalk(this, context, singleplayer, START_DISTANCE, true);
        walk.gather(crate);
        walk.gather(stalk);
        screenshot(context, "gathered");
    }
}
