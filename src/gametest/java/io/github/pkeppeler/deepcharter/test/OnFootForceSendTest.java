package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.Failures;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.FootClient;
import io.github.pkeppeler.deepcharter.test.support.ForceSends;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Server GameTest for the line vanilla logs when a player on foot reports on-ground with no collision under the
 * server's last move, "standing on air - force-sending blocks below" (#1317). A mock player stands in for the client
 * with {@link FootClient}'s physics, so the bore, the fall and the standing are the campaign world's own. Columns
 * 28000 to 28999 are this class's.
 *
 * <p>{@link ForceSends} counts the line for every player, so the test runs in a batch of its own and its two runs one
 * after the other, each counted from its own start. Vanilla sends nothing in the first 200 ticks of a connection and
 * then at most one line in 200, so a run is longer than that and its jumps start after {@link #JUMPS_FROM_TICK}.
 */
public class OnFootForceSendTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int FLOOR = CampaignBands.LAYER_1.minY() + 40;
    private static final int DROP = 24;
    private static final int RUN_TICKS = 700;
    private static final int JUMPS_FROM_TICK = 100;

    /**
     * A mock falls into a carved bore and stands and jumps there with no line. Then the control: a block only the
     * client holds is what the line is for, so the counter and the packets must see it.
     */
    @GameTest(
            environment = CampaignEnvironments.FORCE_SENDS,
            dimension = SAMPLE,
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 2 * 1200)
    public void aPlayerOnFootInACarvedBoreForcesNoSendWhereABlockOnlyTheClientHoldsDoes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Bore plain = Bore.join(helper, "foot-bore", 28000, List.of());
        Bore control = Bore.join(helper, "foot-ledge", 28100, List.of(Shapes.create(-2, 6, -2, 3, 7, 3)));
        boolean[] ready = new boolean[2];
        FarChunks.awaitEntityTicking(
                helper, level, List.of(plain.centre(), control.centre()), index -> ready[index] = true);
        Run[] running = {null};
        TestTicks.each(helper, () -> {
            if (running[0] == null) {
                if (ready[0]) {
                    running[0] = plain.begin(helper);
                }
                return;
            }
            Run run = running[0];
            if (!run.step(helper)) {
                return;
            }
            int lines = run.lines();
            if (run.bore() == plain) {
                if (lines != 0) {
                    throw run.failure(helper, lines, "none");
                }
                if (!ready[1]) {
                    throw Failures.failure(helper, "the control's column was never entity-ticking");
                }
                running[0] = control.begin(helper);
            } else if (lines < 1) {
                throw run.failure(helper, lines, "at least 1");
            } else {
                helper.succeed();
            }
        });
    }

    /**
     * A mock over a bore in {@code column}, which it drops into from the top onto a stone bed. {@code ledges} are
     * shapes the client holds that the server has as air, in blocks from the bore's floor and centre.
     */
    private record Bore(int column, List<VoxelShape> ledges, MockPlayer mock) {
        static Bore join(GameTestHelper helper, String name, int column, List<VoxelShape> ledges) {
            MockPlayer mock = MockPlayers.join(helper, name);
            mock.player().setGameMode(GameType.SURVIVAL);
            mock.player().setPermanentlyInvulnerable(true);
            Bore bore = new Bore(column, ledges, mock);
            mock.teleportTo(helper.getLevel(), bore.top(), 0, 0);
            return bore;
        }

        BlockPos centre() {
            return BlockPos.containing(column + 0.5, FLOOR, column + 0.5);
        }

        private Vec3 top() {
            return new Vec3(column + 0.5, FLOOR + DROP, column + 0.5);
        }

        /** Carves the bore, puts the mock at its top and starts counting. */
        Run begin(GameTestHelper helper) {
            ServerLevel level = helper.getLevel();
            RoomCarver.carve(level, column - 4, column + 3, FLOOR - 1, FLOOR - 1, column - 4, column + 3, Blocks.STONE);
            RoomCarver.carve(
                    level, column - 4, column + 3, FLOOR, FLOOR + DROP + 4, column - 4, column + 3, Blocks.AIR);
            mock.teleportTo(level, top(), 0, 0);
            mock.acknowledgeTeleports();
            List<VoxelShape> shapes = ledges.stream()
                    .map(ledge -> ledge.move(column + 0.5, FLOOR, column + 0.5))
                    .toList();
            // The acknowledgement counts as a position packet this tick, and a second one disconnects the player.
            return new Run(this, new FootClient(mock, shapes), helper.getTick() + 2, ForceSends.total());
        }
    }

    /**
     * A mock standing and jumping in its bore for {@link #RUN_TICKS} ticks from {@code start}, with the lines counted
     * from {@code before}.
     */
    private record Run(Bore bore, FootClient client, long start, int before) {
        /** One client tick; whether the run is over. */
        boolean step(GameTestHelper helper) {
            if (helper.getTick() < start) {
                return false;
            }
            long age = helper.getTick() - start;
            client.tick(age > JUMPS_FROM_TICK && age % 30 == 0);
            return age >= RUN_TICKS;
        }

        int lines() {
            return ForceSends.total() - before;
        }

        RuntimeException failure(GameTestHelper helper, int lines, String wanted) {
            return Failures.failure(
                    helper,
                    "%s: %d force-send lines, wanted %s, with the client at %s and the server at %s",
                    bore.mock().player().getPlainTextName(),
                    lines,
                    wanted,
                    client.position(),
                    bore.mock().player().position());
        }
    }
}
