package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
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
 * Server GameTests for the line vanilla logs when a player on foot reports on-ground with no collision under the server's last move,
 * "standing on air - force-sending blocks below" (#1317). A mock player stands in for the client with {@link FootClient}'s physics, so
 * the bore, the fall and the standing are the campaign world's own. Columns 28000 to 28999 are this class's.
 *
 * <p>Vanilla sends nothing in the first 200 ticks of a connection and then at most one line in 200, so a run is longer than that
 * and its jumps start after {@link #JUMPS_FROM_TICK}.
 */
public class OnFootForceSendTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int DROP = 24;
    private static final int RUN_TICKS = 700;
    private static final int JUMPS_FROM_TICK = 100;

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void aPlayerFallingIntoACarvedBoreAndStandingThereForcesNoSend(GameTestHelper helper) {
        run(helper, "foot-bore", 28000, List.of(), 0);
    }

    /** The control: a block only the client holds is what the line is for, so the counter and the packets must see it. */
    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void aBlockOnlyTheClientHoldsForcesSends(GameTestHelper helper) {
        VoxelShape ledge = Shapes.create(-2, 6, -2, 3, 7, 3);
        run(helper, "foot-ledge", 28100, List.of(ledge), 1);
    }

    /**
     * Drops a mock from the top of a carved bore onto a stone bed and lets it stand and jump there for {@link #RUN_TICKS} ticks.
     * {@code ledges} are shapes the client holds that the server has as air, in blocks from the bore's floor and centre.
     *
     * @param atLeast the lines the run must log: 0 for none at all, otherwise the least that proves the check is reached
     */
    private static void run(GameTestHelper helper, String name, int column, List<VoxelShape> ledges, int atLeast) {
        ServerLevel level = helper.getLevel();
        double x = column + 0.5;
        int floor = CampaignBands.LAYER_1.minY() + 40;
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.SURVIVAL);
        mock.player().setPermanentlyInvulnerable(true);
        mock.teleportTo(level, new Vec3(x, floor + DROP, x), 0, 0);
        ForceSends sends = ForceSends.start(name);
        FootClient[] client = {null};
        long[] start = {-1};
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(x, floor, x), () -> {
            RoomCarver.carve(level, column - 4, column + 3, floor - 1, floor - 1, column - 4, column + 3, Blocks.STONE);
            RoomCarver.carve(
                    level, column - 4, column + 3, floor, floor + DROP + 4, column - 4, column + 3, Blocks.AIR);
            mock.teleportTo(level, new Vec3(x, floor + DROP, x), 0, 0);
            mock.acknowledgeTeleports();
            List<VoxelShape> shapes = ledges.stream()
                    .map(ledge -> ledge.move(column + 0.5, floor, column + 0.5))
                    .toList();
            client[0] = new FootClient(mock, shapes);
            // The acknowledgement counts as a position packet this tick, and a second one disconnects the player.
            start[0] = helper.getTick() + 2;
        });
        TestTicks.each(helper, () -> {
            if (client[0] == null || helper.getTick() < start[0]) {
                return;
            }
            long age = helper.getTick() - start[0];
            client[0].tick(age > JUMPS_FROM_TICK && age % 30 == 0);
            if (age < RUN_TICKS) {
                return;
            }
            int lines = sends.count();
            boolean wanted = atLeast == 0 ? lines == 0 : lines >= atLeast;
            if (!wanted) {
                throw failure(
                        helper,
                        "%d force-send lines, wanted %s, with the client at %s and the server at %s",
                        lines,
                        atLeast == 0 ? "none" : "at least " + atLeast,
                        client[0].position(),
                        mock.player().position());
            }
            helper.succeed();
        });
    }
}
