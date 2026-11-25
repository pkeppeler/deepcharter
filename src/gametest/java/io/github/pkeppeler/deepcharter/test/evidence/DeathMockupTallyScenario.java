package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Tally;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
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
 * Mock-up footage for the death sequence's Tally variant (#1337): a Company Tally hovers over the camera in a dim chamber, lit
 * from a lamp in the floor, looking down as if at a body. One frame per tick with the HUD hidden; the mock-up shakes it and
 * flashes its lens with the laugh.
 */
public final class DeathMockupTallyScenario extends EvidenceScenario {
    private static final int X = 5600;
    private static final int Z = 5600;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    /** The camera lies on the floor, looking north and up at the Tally. */
    private static final float NORTH = 180f;
    private static final float LOOK_UP = -38f;
    private static final double TALLY_UP = 2.1;
    private static final double TALLY_AHEAD = 1.6;
    private static final int LEAD_TICKS = 40;
    private static final int SHOT_TICKS = 90;

    private final DeathFootage footage = new DeathFootage(this);

    @Override
    protected String name() {
        return "death-mockup-tally";
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
                Tally tally = TestEntities.mob(CreatureRegistry.TALLY, one);
                tally.setNoAi(true);
                tally.setNoGravity(true);
                tally.setPos(X + 0.5, FLOOR_Y + TALLY_UP, Z + 0.5);
                tally.setYRot(0f);
                tally.setYHeadRot(0f);
                tally.setYBodyRot(0f);
                one.addFreshEntity(tally);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(
                        one, X + 0.5, FLOOR_Y + 0.1 - 1.2, Z + 0.5 + TALLY_AHEAD, Set.of(), NORTH, LOOK_UP, true);
            });
            awaitWorld(context);
            ClientWait.until(context, "the player in layer 1", client -> TestLayers.clientIsIn(client, 1));
            ClientWait.chunksAround(context, X, Z, 1);
            context.waitTicks(60);
            ClientChecks.withHudHidden(context, () -> footage.steps(context, LEAD_TICKS + SHOT_TICKS));
            footage.event("end");
            footage.writeEvents();
        }
    }

    /** A square of {@code block} {@code radius} blocks out from the Tally, from {@code yFrom} to {@code yTo}. */
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
