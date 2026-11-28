package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
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
 * Evidence scenario "void-below" (#1387): a pilot with 32 hull drills down a shaft of layer 1 towards the breach crust,
 * which takes 8 hull a row, 24 in all. Under the crust is a cave: 8 rows of open space over stone, a 9-block free fall
 * from the last crust row that would land hard for about 19 hull. Before #1387 the HUD showed nothing (32 outlasts 24);
 * now the crust warning adds the drop: {@code CRUST 3 DOWN HULL 32, IT TAKES 24 + DROP 19}. The pod bores the crust,
 * and as it falls into the cave the pilot holds the rotor whenever the pod sinks faster than half a block a tick, so it
 * lands with the 8 hull the crust left.
 *
 * <p>The pod is a Prospector with the tier 3 console, as in {@link PodBraceScenario}: the stock console's plate fails
 * in layer 1's signal-decay band near the floor, and tier 3 never decays. The drill's hollow pitch over the last two
 * rows is sound only, so the recording cannot show it.
 */
public class VoidBelowScenario extends EvidenceScenario {
    private static final int X = 4900;
    private static final int Z = 5000;
    private static final int FLOOR = TestLayers.worldY(1, 0);
    private static final int CRUST_TOP_Y = TestLayers.worldY(1, LayerTuning.DEFAULT.crustThickness());
    private static final int FLOOR_Y = CRUST_TOP_Y + 6;
    private static final int RADIUS = 6;
    private static final int TICKS_PER_FRAME = 4;
    private static final int MAX_TICKS = 6000;
    private static final float LOOK_DOWN = 30f;
    private static final float FACING_EAST = -90f;
    private static final float HULL = 32f;
    private static final float CRUST = 24f;
    /**
     * The rotor holds the pod while it sinks faster than this, in blocks a tick: under the hard landing speed of 0.7.
     */
    private static final double BRAKE_ABOVE_SINK = 0.5;

    @Override
    protected String name() {
        return "void-below";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                build(one);
            });
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X, Z, RADIUS);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), FACING_EAST, LOOK_DOWN, true);
            });
            ClientWait.arrived(context, singleplayer.getServer());
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, one);
                pod.setPos(X, FLOOR_Y, Z);
                pod.setYRot(FACING_EAST);
                one.addFreshEntity(pod);
                ScannerPods.fit(server, player, pod, ComponentTrack.FUEL_TANK, 2);
                ScannerPods.fit(server, player, pod, ComponentTrack.CONSOLE, ComponentTrack.CONSOLE.maxTier());
                pod.setFuel(100f);
                pod.setHull(HULL);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pilot seated in the pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.clientIsIn(client, 1));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(60);
            // Founding the charter queued the employer's transmission; it would cover the HUD this scenario shows.
            context.runOnClient(client -> TransmissionOverlay.reset());
            for (int i = 0; i < 4; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "void-below-open-room");

            // Hold the drill down to the top of the crust.
            context.getInput().holdKey(options -> options.keySprint);
            boolean shot = false;
            int ticks = 0;
            while (feetY(singleplayer) > CRUST_TOP_Y + 0.01 && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
                if (!shot && feetY(singleplayer) < CRUST_TOP_Y + 4) {
                    shot = true;
                    screenshot(context, "void-below-warning");
                }
            }
            if (feetY(singleplayer) > CRUST_TOP_Y + 0.01) {
                throw new AssertionError("The pod should drill down to the crust, it is at " + feetY(singleplayer));
            }

            // Through the crust; once the pod falls past the floor, brake with the rotor.
            ticks = 0;
            while (feetY(singleplayer) > FLOOR - 0.3 && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            context.getInput().releaseKey(options -> options.keySprint);
            screenshot(context, "void-below-breakthrough");
            ticks = 0;
            boolean braking = false;
            while (!landed(singleplayer) && ticks < MAX_TICKS) {
                boolean fast = sinkSpeed(singleplayer) > BRAKE_ABOVE_SINK;
                if (fast != braking) {
                    braking = fast;
                    if (fast) {
                        context.getInput().holdKey(options -> options.keyJump);
                    } else {
                        context.getInput().releaseKey(options -> options.keyJump);
                    }
                }
                context.waitTick();
                ticks++;
                if (ticks % TICKS_PER_FRAME == 0) {
                    frame(context);
                }
            }
            context.getInput().releaseKey(options -> options.keyJump);
            context.waitTicks(20);
            // The first breach's transmission would cover the plate with the hull the pod kept.
            context.runOnClient(client -> TransmissionOverlay.reset());
            for (int i = 0; i < 6; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "void-below-braked");
            float hull = singleplayer
                    .getServer()
                    .computeOnServer(server -> TestEntities.vehicle(
                                    server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                            .hull());
            if (Math.abs(hull - (HULL - CRUST)) > 0.5f) {
                throw new AssertionError("A pod braked into the cave lands with what the crust left, " + (HULL - CRUST)
                        + "; it has " + hull);
            }
        }
    }

    private static double feetY(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> TestEntities.vehicle(
                                server.getPlayerList().getPlayers().getFirst())
                        .getY());
    }

    private static double sinkSpeed(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> -TestEntities.vehicle(
                                server.getPlayerList().getPlayers().getFirst())
                        .getDeltaMovement()
                        .y);
    }

    /** Whether the pod stands on the cave's floor, below the crust. */
    private static boolean landed(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> {
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            return pod.onGround() && pod.getY() < FLOOR - 1;
        });
    }

    /**
     * A stone bed over three rows of breach crust over a cave, under an open room. Lamps sit in the bore's walls, flush
     * with the 3 x 3 bore of the Prospector, so none is drilled away; one in the cave's bed under the bore lights the
     * landing, and more stand round the cave's edge.
     */
    private static void build(ServerLevel level) {
        RoomCarver.carve(
                level,
                X - RADIUS,
                X + RADIUS,
                FLOOR,
                CRUST_TOP_Y - 1,
                Z - RADIUS,
                Z + RADIUS,
                LayerBlocks.BREACH_CRUST);
        RoomCarver.carvePocketBelow(level, 1, X - RADIUS, X + RADIUS, Z - RADIUS, Z + RADIUS);
        RoomCarver.carve(level, X - RADIUS, X + RADIUS, CRUST_TOP_Y, FLOOR_Y - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
        RoomCarver.carve(
                level,
                new BlockPos(X - RADIUS, FLOOR_Y, Z - RADIUS),
                new BlockPos(X + RADIUS, FLOOR_Y + 9, Z + RADIUS),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        for (BlockPos lamp : new BlockPos[] {
            new BlockPos(X + RADIUS, FLOOR_Y + 3, Z),
            new BlockPos(X - RADIUS, FLOOR_Y + 3, Z),
            new BlockPos(X, FLOOR_Y + 3, Z + RADIUS),
            new BlockPos(X, FLOOR_Y + 3, Z - RADIUS),
            new BlockPos(X - 2, FLOOR_Y - 2, Z),
            new BlockPos(X + 2, FLOOR_Y - 4, Z),
            new BlockPos(X - 2, FLOOR_Y - 6, Z),
            new BlockPos(X, FLOOR - 9, Z),
            new BlockPos(X + RADIUS, FLOOR - 3, Z),
            new BlockPos(X - RADIUS, FLOOR - 3, Z),
            new BlockPos(X, FLOOR - 3, Z + RADIUS),
            new BlockPos(X, FLOOR - 3, Z - RADIUS),
            new BlockPos(X + RADIUS, FLOOR - 7, Z),
            new BlockPos(X - RADIUS, FLOOR - 7, Z)
        }) {
            level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
    }
}
