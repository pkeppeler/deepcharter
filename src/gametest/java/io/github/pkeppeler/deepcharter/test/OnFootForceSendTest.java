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

/**
 * Server GameTests for a player on foot in a carved bore of the campaign world (#1317): vanilla logs "standing on air - force-sending
 * blocks below" when the client reports on-ground while the server's last move found nothing below, and each line makes the client
 * rebuild sections. Columns 28000 to 28999 are this class's.
 */
public class OnFootForceSendTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int DROP = 24;

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void probeJumpingDownACarvedBoreForcesNoSend(GameTestHelper helper) {
        run(helper, "foot-probe", 28000, false);
    }

    @GameTest(environment = CampaignEnvironments.D, dimension = SAMPLE, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void probeControlAClientBlockTheServerHasAsAirForcesSends(GameTestHelper helper) {
        run(helper, "foot-control", 28100, true);
    }

    private static String diag(MockPlayer mock) {
        try {
            var listener = mock.player().connection;
            var f = listener.getClass().getDeclaredField("awaitingPositionFromClient");
            f.setAccessible(true);
            var g = listener.getClass().getDeclaredField("awaitingTeleport");
            g.setAccessible(true);
            String extra = "";
            for (String n : new String[] {"waitingForRespawn", "clientLoadedTimeoutTimer", "receivedMovePacketCount", "knownMovePacketCount", "lastGoodY", "firstGoodY"}) {
                var h = listener.getClass().getDeclaredField(n);
                h.setAccessible(true);
                extra += " " + n + "=" + h.get(listener);
            }
            return extra + " awaitPos=" + f.get(listener) + " awaitId=" + g.get(listener) + " passenger=" + mock.player().isPassenger() + " tick=" + mock.player().tickCount;
        } catch (ReflectiveOperationException e) {
            return e.toString();
        }
    }

    private static void run(GameTestHelper helper, String name, int column, boolean phantom) {
        ServerLevel level = helper.getLevel();
        double x = column + 0.5;
        double z = column + 0.5;
        int floor = CampaignBands.LAYER_1.minY() + 40;
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.SURVIVAL);
        mock.player().setPermanentlyInvulnerable(true);
        mock.teleportTo(level, new Vec3(x, floor + DROP, z), 0, 0);
        ForceSends sends = ForceSends.start(name);
        FootClient[] client = {null};
        long[] start = {-1};
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(x, floor, z), () -> {
            RoomCarver.carve(level, column - 4, column + 3, floor - 1, floor - 1, column - 4, column + 3, Blocks.STONE);
            RoomCarver.carve(level, column - 4, column + 3, floor, floor + DROP + 4, column - 4, column + 3, Blocks.AIR);
            mock.teleportTo(level, new Vec3(x, floor + DROP, z), 0, 0);
            mock.acknowledgeTeleports();
            client[0] = new FootClient(mock, phantom ? List.of(Shapes.create(column - 2, floor + 6, column - 2, column + 3, floor + 7, column + 3)) : List.of());
            start[0] = helper.getTick() + 2;
        });
        TestTicks.each(helper, () -> {
            if (client[0] == null || helper.getTick() < start[0]) {
                return;
            }
            long t = helper.getTick() - start[0];
            client[0].tick(t > 100 && t % 30 == 0);
            if (t < 12 && name.equals("foot-probe")) {
                System.out.println("TICK " + t + " client=" + client[0].position() + " server=" + mock.player().position() + " online=" + mock.isOnline() + " diag=" + diag(mock));
            }
            if (t == 700) {
                System.out.println("DIAG " + diag(mock));
                System.out.println("FORCESENDS " + name + " count=" + sends.count() + " packets=" + client[0].packets()
                        + " at=" + client[0].position() + " server=" + mock.player().position() + " srvGround=" + mock.player().onGround() + " vcb=" + mock.player().verticalCollisionBelow);
                if ((sends.count() > 0) != phantom) {
                    throw failure(helper, "%d force-sends, client at %s", sends.count(), client[0].position());
                }
                helper.succeed();
            }
        });
    }
}
