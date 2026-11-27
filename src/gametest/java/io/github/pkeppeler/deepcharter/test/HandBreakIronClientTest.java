package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** An iron pick on the crust of layer 2, which the block destroys ({@link HandBreakCases}). */
public class HandBreakIronClientTest extends HandBreakCases {
    @Override
    void cases(ClientGameTestContext context, TwoPlayerServer two) {
        // An iron pick is destroyed by the block, but not before it: the client goes on mining while the server
        // wears it, predicts the break at the end, and both sides agree the block is gone and the pick with it.
        BlockPos deep = prepare(two, 2, X + 40);
        awaitCrust(context, deep);
        hold(two, new ItemStack(Items.IRON_PICKAXE));
        EvidencePins.attackHeld = true;
        int ironTicks = mine(context, deep, BITING_LIMIT_TICKS, true, true);
        EvidencePins.attackHeld = false;
        require(
                ironTicks >= 1000,
                "the crust broke after only " + ironTicks + " ticks of an iron pick, not about 1200");
        for (int tick = 0; tick < SETTLE_TICKS; tick++) {
            context.waitTicks(1); // tick-wait: the server's correction of a ghost break arrives within a few ticks
            require(!clientHolds(context, deep), "the crust came back on the client after an iron pick broke it");
        }
        require(!serverHolds(two, 2, deep), "the client broke the crust but the server still has it");
        require(
                two.server()
                        .computeOnServer(
                                server -> realPlayer(server).getMainHandItem().isEmpty()),
                "the iron pick was not destroyed by the block");
    }
}
