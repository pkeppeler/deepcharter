package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.test.mixin.ServerGamePacketListenerAccess;
import io.github.pkeppeler.deepcharter.test.support.ForceSendProbe;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for {@link ForceSendProbe}'s verdict (#1317). A mock player's in-air packet and landing packet reach
 * the server in one tick, so no server tick moves the player between them and vanilla's real check logs the line. Over
 * a server floor the probe calls the line supported (vanilla's stale-move race); over server air, unsupported (the
 * client stands on a block the server lacks).
 */
public class ForceSendProbeTest {
    /** Where the player lands, inside the structure; the floor block is the one under it. */
    private static final Vec3 LANDING = new Vec3(1.5, 2, 1.5);

    private static final BlockPos FLOOR = new BlockPos(1, 1, 1);

    @GameTest
    public void aLandingOverAServerFloorIsSupported(GameTestHelper helper) {
        helper.setBlock(FLOOR, Blocks.STONE);
        List<ForceSendProbe.Shot> shots = land(helper, "force-send-floor");
        expect(helper, shots.size() == 1, "one force-send over the floor, got %s", shots);
        expect(helper, shots.getFirst().serverSupported(), "the floor holds the landing up: %s", shots);
        helper.succeed();
    }

    @GameTest
    public void aLandingOverServerAirIsUnsupported(GameTestHelper helper) {
        helper.setBlock(FLOOR, Blocks.AIR);
        helper.setBlock(FLOOR.below(), Blocks.AIR);
        List<ForceSendProbe.Shot> shots = land(helper, "force-send-air");
        expect(helper, shots.size() == 1, "one force-send over air, got %s", shots);
        expect(helper, !shots.getFirst().serverSupported(), "air holds nothing up: %s", shots);
        helper.succeed();
    }

    /** Sends an in-air packet and then a landing packet in one server tick, and returns the force-sends they made. */
    private static List<ForceSendProbe.Shot> land(GameTestHelper helper, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        ServerPlayer player = mock.player();
        player.setGameMode(GameType.SURVIVAL);
        Vec3 landing = helper.absoluteVec(LANDING);
        mock.teleportTo(helper.getLevel(), landing.add(0, 1.5, 0), 0f, 0f);
        mock.acknowledgeTeleports();
        ServerGamePacketListenerAccess access = (ServerGamePacketListenerAccess) player.connection;
        access.deepcharter$setLastSupportedBlocksSend(access.deepcharter$tickCount() - 200);
        List<ForceSendProbe.Shot> shots = new ArrayList<>();
        ForceSendProbe.watch(true);
        try {
            player.connection.handleMovePlayer(
                    new ServerboundMovePlayerPacket.Pos(landing.x, landing.y + 1, landing.z, false, false));
            // A second position packet before the client's tick end disconnects the player.
            player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
            player.connection.handleMovePlayer(
                    new ServerboundMovePlayerPacket.Pos(landing.x, landing.y, landing.z, true, false));
            ForceSendProbe.Shot shot;
            while ((shot = ForceSendProbe.poll()) != null) {
                if (shot.player().equals(player.getUUID())) {
                    shots.add(shot);
                }
            }
        } finally {
            ForceSendProbe.watch(false);
        }
        return shots;
    }
}
