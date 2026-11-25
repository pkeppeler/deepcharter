package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Mock-up footage for the death sequence (#1337): a pod already worn down to its last few points of hull sits in a lit
 * layer-1 chamber when the floor under it gives way to lava. The lava burns the hull to nothing, the pod is a wreck and its
 * pilot dies with it (the wreck kill, {@code CrewFate}). One frame per tick, up to the tick before the vanilla death screen.
 */
public final class DeathMockupPodScenario extends EvidenceScenario {
    private static final int X = 5200;
    private static final int Z = 5200;
    private static final int FLOOR_Y = LIVE_GAUGES_FLOOR_Y;
    private static final float LOOK_DOWN = 22f;
    private static final float HULL_LEFT = 28f;
    private static final int DEATH_LIMIT_TICKS = 600;

    private final DeathFootage footage = new DeathFootage(this);

    @Override
    protected String name() {
        return "death-mockup-pod";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                box(one, FLOOR_Y - 2, FLOOR_Y - 1, 6, Blocks.STONE);
                box(one, FLOOR_Y, FLOOR_Y + 8, 6, Blocks.STONE);
                // room-carver: a room cut inside the solid stone box built just above, so no worldgen lava is in or
                // beside it; the scenario places its own lava
                box(one, FLOOR_Y, FLOOR_Y + 7, 5, Blocks.AIR);
                for (BlockPos lamp : new BlockPos[] {
                    new BlockPos(X + 5, FLOOR_Y + 3, Z), new BlockPos(X - 5, FLOOR_Y + 3, Z),
                    new BlockPos(X, FLOOR_Y + 3, Z + 5)
                }) {
                    one.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
                }
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X + 0.5, FLOOR_Y, Z + 0.5, Set.of(), 0, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X + 0.5, FLOOR_Y, Z + 0.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
                pod.damageHull(pod.maxHull() - HULL_LEFT);
            });
            ClientWait.until(
                    context,
                    "the player riding a pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.clientIsIn(client, 1));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(40);
            footage.steps(context, 40);

            footage.event("lava");
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                for (int x = X - 2; x <= X + 2; x++) {
                    for (int z = Z - 2; z <= Z + 2; z++) {
                        one.setBlock(new BlockPos(x, FLOOR_Y - 1, z), Blocks.LAVA.defaultBlockState(), 3);
                        one.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.LAVA.defaultBlockState(), 3);
                    }
                }
            });
            boolean wrecked = false;
            for (int i = 0; i < DEATH_LIMIT_TICKS; i++) {
                if (DeathFootage.dead(context)) {
                    footage.event("death");
                    break;
                }
                footage.step(context);
                if (!wrecked && hull(singleplayer) <= 0f) {
                    footage.event("wreck");
                    wrecked = true;
                }
                if (i % 10 == 0) {
                    footage.event("hull " + hull(singleplayer));
                }
            }
            footage.writeEvents();
        }
    }

    /** The hull of the pod the pilot rides, or 0 once they are out of it (a wreck throws its crew off). */
    private static float hull(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server ->
                        server.getPlayerList().getPlayers().getFirst().getVehicle() instanceof PodEntity pod
                                ? pod.hull()
                                : 0f);
    }

    /** A square of {@code block} {@code radius} blocks out from the pod's column, from {@code yFrom} to {@code yTo}. */
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
