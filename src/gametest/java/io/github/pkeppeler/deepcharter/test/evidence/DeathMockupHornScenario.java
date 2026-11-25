package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Mock-up footage for the death sequence's horn variant (#1337): a Company PA horn hangs in a sealed, unlit chamber, lit
 * only from a lamp in the floor beneath it. The camera turns slowly until the horn's mouth fills the view. One frame per
 * tick, with the HUD hidden; the mock-up grades it to soot and sepia and lays the laugh over it.
 */
public class DeathMockupHornScenario extends EvidenceScenario {
    private static final int X = 5400;
    private static final int Z = 5400;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final int HORN_Y = FLOOR_Y + 2;
    private static final double EYE_HEIGHT = 1.62;
    private static final double DISTANCE = 1.45;
    /** Looking north, at the horn's south mouth. */
    private static final float NORTH = 180f;
    private static final float START_TURN = 70f;
    private static final float START_PITCH = -14f;
    private static final int TURN_TICKS = 70;
    private static final int HOLD_TICKS = 50;

    private final DeathFootage footage = new DeathFootage(this);

    @Override
    protected String name() {
        return "death-mockup-horn";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                box(one, FLOOR_Y - 1, FLOOR_Y + 7, 5, Blocks.STONE);
                // room-carver: a room cut inside the solid stone box built just above, so no worldgen lava is in or beside it
                box(one, FLOOR_Y, FLOOR_Y + 6, 4, Blocks.AIR);
                one.setBlock(new BlockPos(X, FLOOR_Y - 1, Z), Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
                one.setBlock(
                        new BlockPos(X, HORN_Y, Z),
                        AmbienceRegistry.COMPANY_HORN.defaultBlockState(),
                        Block.UPDATE_ALL);
                one.setBlock(new BlockPos(X, HORN_Y + 1, Z), Blocks.IRON_CHAIN.defaultBlockState(), Block.UPDATE_ALL);
                one.setBlock(new BlockPos(X, HORN_Y + 2, Z), Blocks.IRON_CHAIN.defaultBlockState(), Block.UPDATE_ALL);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(
                        one,
                        X + 0.5,
                        HORN_Y + 0.45 - EYE_HEIGHT,
                        Z + 0.5 + DISTANCE,
                        Set.of(),
                        NORTH + START_TURN,
                        START_PITCH,
                        true);
            });
            awaitWorld(context);
            ClientWait.until(context, "the player in layer 1", client -> TestLayers.clientIsIn(client, 1));
            context.waitTicks(60);
            ClientChecks.withHudHidden(context, () -> {
                for (int i = 0; i <= TURN_TICKS + HOLD_TICKS; i++) {
                    double t = Math.min(1.0, i / (double) TURN_TICKS);
                    double eased = 1 - Math.pow(1 - t, 3);
                    float yaw = (float) (NORTH + START_TURN * (1 - eased));
                    float pitch = (float) (START_PITCH * (1 - eased));
                    context.runOnClient(client -> {
                        ClientSession.player(client).setYRot(yaw);
                        ClientSession.player(client).setXRot(pitch);
                    });
                    footage.step(context);
                }
            });
            footage.event("end");
            footage.writeEvents();
        }
    }

    /** A square of {@code block} {@code radius} blocks out from the horn, from {@code yFrom} to {@code yTo}. */
    private static void box(ServerLevel level, int yFrom, int yTo, int radius, Block block) {
        for (int x = X - radius; x <= X + radius; x++) {
            for (int y = yFrom; y <= yTo; y++) {
                for (int z = Z - radius; z <= Z + radius; z++) {
                    level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }
}
