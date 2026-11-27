package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Client GameTest: the real client and the mock player both cross a breach, in place in the campaign level. */
public class BreachCrossingClientTest implements FabricClientGameTest {
    private static final double X = 2000.5;
    private static final double Z = 2000.5;
    private static final int CROSSING_TICKS = 600;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
            MockPlayer mock = two.mock();
            UUID mockId = mock.player().getUUID();
            UUID clientId = two.server()
                    .computeOnServer(server -> realPlayer(server, mock).getUUID());

            // Both start in layer 1 above a shaft through the crust and stone, over a pocket in layer 2, a block apart.
            two.server().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                openShaft(one, X);
                openShaft(one, X + 2);
                realPlayer(server, mock).teleportTo(one, X, TestLayers.worldY(1, 8), Z, Set.of(), 0, 0, true);
                mock.teleportTo(one, TestLayers.vec(1, X + 2, 8, Z), 0, 0);
            });

            // The real client falls under its own physics. The mock has no client, so drop it a block a tick.
            for (int tick = 0; tick < CROSSING_TICKS && !crossed(context, two, mockId, clientId); tick++) {
                two.server().runOnServer(server -> {
                    ServerPlayer player = mock.player();
                    if (TestLayers.isIn(player, 1)) {
                        player.setPos(player.getX(), player.getY() - 1, player.getZ());
                    }
                });
                context.waitTick();
            }

            require(
                    crossed(context, two, mockId, clientId),
                    "Not both players crossed into layer 2 within " + CROSSING_TICKS + " ticks");
            context.takeScreenshot("breach-crossing-layer-2");
        }
    }

    private static boolean inLayerTwo(MinecraftServer server, UUID id) {
        ServerPlayer player =
                Objects.requireNonNull(server.getPlayerList().getPlayer(id), () -> "player " + id + " is not online");
        return TestLayers.isIn(player, 2);
    }

    /** The client is in layer 2 and sees player 2 there; the server agrees about both. */
    private static boolean crossed(ClientGameTestContext context, TwoPlayerServer two, UUID mockId, UUID clientId) {
        boolean clientSees = context.computeOnClient(client -> client.level != null
                && TestLayers.clientIsIn(client, 2)
                && client.level.getPlayerByUUID(mockId) != null);
        boolean serverAgrees =
                two.server().computeOnServer(server -> inLayerTwo(server, mockId) && inLayerTwo(server, clientId));
        return clientSees && serverAgrees;
    }

    private static ServerPlayer realPlayer(MinecraftServer server, MockPlayer mock) {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> player != mock.player())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The real client's player is not on the server"));
    }

    private static void openShaft(ServerLevel level, double x) {
        BlockPos column = BlockPos.containing(x, 0, Z);
        RoomCarver.carvePocketBelow(level, 1, column.getX(), column.getX(), column.getZ(), column.getZ());
        RoomCarver.carve(
                level,
                column.atY(TestLayers.band(level, 1).minY()),
                column.atY(TestLayers.band(level, 1).minY() + 10),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
    }
}
