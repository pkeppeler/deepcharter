package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.HandBreakChecks;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A bare hand and a diamond pick on the crust of layer 1 ({@link HandBreakCases}). */
public class HandBreakClientTest extends HandBreakCases implements FabricClientGameTest {
    @Override
    void cases(ClientGameTestContext context, TwoPlayerServer two) {
        BlockPos target = prepare(two, 1, X);
        awaitCrust(context, target);

        // A bare hand is no tool: the swing refuses on the client, and the block stays on both sides.
        hold(two, ItemStack.EMPTY);
        EvidencePins.attackHeld = true;
        mine(context, target, BARE_HAND_TICKS, false, false);
        EvidencePins.attackHeld = false;
        require(
                clientHolds(context, target),
                "a bare hand broke the crust on the client: " + clientFacts(context, target));
        require(serverHolds(two, 1, target), "a bare hand broke the crust on the server");

        // A diamond pick: the client's prediction and the server's check agree.
        hold(two, new ItemStack(Items.DIAMOND_PICKAXE));
        EvidencePins.attackHeld = true;
        mine(context, target, MIDWAY_TICKS, true, false);
        // Half way, the player must be able to SEE the work: the pick worn on the client, and the crack well along.
        HandBreakChecks.requireWorkShows(
                context,
                MIDWAY_TICKS,
                () -> two.server()
                        .computeOnServer(
                                server -> realPlayer(server).getMainHandItem().getDamageValue()),
                () -> two.server().computeOnServer(server -> HandBreakChecks.serverFacts(server, target)));
        int ticks = MIDWAY_TICKS + mine(context, target, BITING_LIMIT_TICKS - MIDWAY_TICKS, true, true);
        EvidencePins.attackHeld = false;
        require(ticks >= 1000, "the crust broke after only " + ticks + " ticks, not about 1200");
        for (int tick = 0; tick < SETTLE_TICKS; tick++) {
            context.waitTicks(1); // tick-wait: the server's correction of a ghost break arrives within a few ticks
            require(!clientHolds(context, target), "the crust came back on the client after it broke (a ghost break)");
        }
        require(!serverHolds(two, 1, target), "the client broke the crust but the server still has it");
        context.takeScreenshot("hand-break-crust-gone");
    }
}
