package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "tow-chain" (#508): a Mole tows a pod through a sealed, dark stone hall lit by two lanterns, and the tow line is a
 * chain. The line hangs while the pod stands behind, goes taut as the Mole pulls it, hangs again when the Mole backs up, and is seen
 * close and from range. Then the braided steel cable test pack is switched on, the way F3+T would, and the same pull is filmed again.
 */
public class TowChainScenario extends EvidenceScenario {
    private static final int X = 2300;
    private static final int Z = 2300;
    // fixed-pos: a stone slab the scenario builds in the overworld sky, clear of the terrain
    private static final int FLOOR_Y = 200;
    private static final int HEIGHT = 12;
    private static final int PULL_TICKS = 90;
    private static final int BACK_TICKS = 40;
    private static final int STANDING_FRAMES = 6;
    /** The watcher is this far west of the line (the lanterns are east and west of the hall's middle). */
    private static final double CLOSE = 4;

    private static final double RANGE = 11;

    @Override
    protected String name() {
        return "tow-chain";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            UUID[][] pods = {null};
            two.server().runOnServer(server -> {
                ServerLevel level = server.overworld();
                // A sealed hall: stone all round, and the lanterns hang from its roof. No sky reaches it, so only the
                // lanterns light it.
                TowStage.fill(level, X - 40, X + 40, FLOOR_Y - 4, FLOOR_Y + HEIGHT + 3, Z - 30, Z + 80, Blocks.STONE);
                TowStage.fill(level, X - 25, X + 25, FLOOR_Y, FLOOR_Y + HEIGHT, Z - 18, Z + 70, Blocks.AIR);
                for (int z : new int[] {Z - 4, Z + 14, Z + 40, Z + 64}) {
                    for (int x : new int[] {X - 9, X + 9}) {
                        level.setBlock(
                                new BlockPos(x, FLOOR_Y + HEIGHT, z),
                                Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true),
                                3);
                    }
                }
                // Two on the floor behind the watcher's side of the line light the pods' faces, so the chain is seen
                // against something.
                for (int z : new int[] {Z - 4, Z + 14}) {
                    level.setBlock(new BlockPos(X - 7, FLOOR_Y, z), Blocks.LANTERN.defaultBlockState(), 3);
                }
                TowStage.prepare(level, two, new Vec3(X - 15, FLOOR_Y + 3, Z));
                Vec3 at = new Vec3(X + 0.5, FLOOR_Y, Z + 0.5);
                pods[0] = TowStage.pair(level, two, at, at.add(0, 0, -4.5), false);
            });
            UUID tower = pods[0][0];
            UUID towed = pods[0][1];
            context.waitTicks(TowStage.CHAT_FADE_TICKS);
            for (int i = 0; i < STANDING_FRAMES; i++) {
                look(context, two, tower, towed, CLOSE);
                context.waitTicks(TowStage.TICKS_PER_FRAME);
            }
            screenshot(context, "chain-hanging-close");
            look(context, two, tower, towed, RANGE);
            screenshot(context, "chain-hanging-at-range");
            drive(context, two, tower, towed, TowStage.SOUTH, PULL_TICKS, CLOSE);
            screenshot(context, "chain-taut-close");
            look(context, two, tower, towed, RANGE);
            screenshot(context, "chain-taut-at-range");

            ClientPacks.enable(context, TestPacks.TOW_CABLE_BRAID);
            try {
                look(context, two, tower, towed, CLOSE);
                screenshot(context, "braided-cable-taut-close");
                look(context, two, tower, towed, RANGE);
                screenshot(context, "braided-cable-taut-at-range");
                drive(context, two, tower, towed, TowStage.NORTH, BACK_TICKS, CLOSE);
                screenshot(context, "braided-cable-hanging-close");
            } finally {
                ClientPacks.disable(context, TestPacks.TOW_CABLE_BRAID);
            }
        }
    }

    private void drive(
            ClientGameTestContext context,
            TwoPlayerServer two,
            UUID tower,
            UUID towed,
            float yaw,
            int ticks,
            double distance) {
        two.server().runOnServer(server -> {
            two.mock().player().setYRot(yaw);
            two.mock().setInput(TowStage.FORWARD);
        });
        for (int done = 0; done < ticks; done += TowStage.TICKS_PER_FRAME) {
            context.waitTicks(TowStage.TICKS_PER_FRAME);
            look(context, two, tower, towed, distance);
        }
        two.server().runOnServer(server -> two.mock().releaseInput());
        for (int done = 0; done < 15; done += TowStage.TICKS_PER_FRAME) {
            context.waitTicks(TowStage.TICKS_PER_FRAME);
            look(context, two, tower, towed, distance);
        }
    }

    /** Films the middle between the two pods from the west, {@code distance} blocks off and a little above it. */
    private void look(ClientGameTestContext context, TwoPlayerServer two, UUID tower, UUID towed, double distance) {
        two.server().runOnServer(server -> {
            Vec3 at = TestEntities.require(server.overworld(), tower)
                    .position()
                    .add(TestEntities.require(server.overworld(), towed).position())
                    .scale(0.5)
                    .add(0, 1.0, 0);
            TowStage.film(server.overworld(), two, at.add(-distance, distance * 0.2, 0), at);
        });
        context.runOnClient(client -> client.gui.toastManager().clear());
        frame(context);
    }
}
