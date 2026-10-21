package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
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
 * Evidence scenario "pod-heat-shield" (#482): a pod with the heat shield and a rack of 12 slag bricks sits in a sealed stone room, and lava floods its floor. The
 * HUD says "GLAZE HOLDING" and counts the bricks down while the hull stays at 100 and the glaze crackles. When the bricks are spent the HUD says "GLAZE SPENT" and
 * "HULL BURNING", and the hull starts to fall. The pilot pulls the rotor and climbs out of the lava with most of the hull left.
 */
public class PodHeatShieldScenario extends EvidenceScenario {
    static final int X = 5100;
    static final int Z = 5000;
    static final int FLOOR_Y = LayerTuning.DEFAULT.crustThickness() + 1;
    private static final int TICKS_PER_FRAME = 4;
    private static final int MAX_TICKS = 900;
    private static final int RACK = 12;
    private static final float LOOK_DOWN = 25f;
    private static final int OUT_OF_LAVA_Y = FLOOR_Y + 3;

    @Override
    protected String name() {
        return "pod-heat-shield";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                carveRoom(one, FLOOR_Y);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), 0, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X, FLOOR_Y, Z);
                one.addFreshEntity(pod);
                ScannerPods.fit(server, player, pod, ComponentTrack.HEAT_SHIELD, 1);
                PodLining.modify(pod, state -> new PodLining.State(0, RACK, 0, false, false));
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the pilot seated in the shielded pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.isIn(ClientLight.clientLevel(client), 1));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(30);
            // Founding the charter queued the employer's transmission; it would cover the HUD lines this scenario
            // shows.
            context.runOnClient(client -> TransmissionOverlay.reset());
            for (int i = 0; i < 6; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "pod-heat-shield-before");

            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                for (int x = X - 2; x <= X + 2; x++) {
                    for (int z = Z - 2; z <= Z + 2; z++) {
                        one.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.LAVA.defaultBlockState(), 3);
                    }
                }
            });
            // The glaze holds: the bricks count down and the hull stays full.
            ClientWait.until(
                    context,
                    "the glaze holding",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && pod.hullBurning()
                            && pod.glazeBricks() > 0);
            context.runOnClient(client -> TransmissionOverlay.reset());
            int ticks = 0;
            boolean shot = false;
            while (bricks(singleplayer) > 0 && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
                if (!shot && bricks(singleplayer) <= RACK / 2) {
                    shot = true;
                    screenshot(context, "pod-heat-shield-holding");
                }
            }
            if (bricks(singleplayer) > 0 || !shot || podHull(singleplayer) < 99f) {
                throw new AssertionError("The glaze should burn the whole rack and keep the hull full: bricks "
                        + bricks(singleplayer) + ", hull " + podHull(singleplayer));
            }
            // The bricks are spent: the hull burns. A few frames of it, then the pilot climbs out.
            for (int i = 0; i < 8; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "pod-heat-shield-spent");
            context.getInput().holdKey(options -> options.keyJump);
            ticks = 0;
            while (podY(singleplayer) < OUT_OF_LAVA_Y && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            context.getInput().releaseKey(options -> options.keyJump);
            if (podY(singleplayer) < OUT_OF_LAVA_Y) {
                throw new AssertionError("The pilot should climb out of the lava, the pod is at " + podY(singleplayer));
            }
            for (int i = 0; i < 6; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "pod-heat-shield-out");
            float hull = podHull(singleplayer);
            if (hull < 50f || hull >= 100f) {
                throw new AssertionError("The pod should leave the lava hurt but alive, the hull is " + hull);
            }
        }
    }

    /** The sealed stone room of layer 1 the shield scenarios stage in, lit by glowstone. */
    static void carveRoom(ServerLevel one, int floorY) {
        RoomCarver.carve(one, X - 6, X + 6, floorY - 1, floorY + 13, Z - 6, Z + 6, Blocks.STONE);
        RoomCarver.carve(
                one,
                new BlockPos(X - 5, floorY, Z - 5),
                new BlockPos(X + 5, floorY + 12, Z + 5),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        for (BlockPos lamp : new BlockPos[] {
            new BlockPos(X + 5, floorY + 4, Z), new BlockPos(X - 5, floorY + 4, Z),
            new BlockPos(X, floorY + 4, Z + 5), new BlockPos(X, floorY + 4, Z - 5),
            new BlockPos(X + 5, floorY + 9, Z), new BlockPos(X - 5, floorY + 9, Z)
        }) {
            one.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
    }

    static int bricks(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> TestEntities.vehicle(
                                server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                        .glazeBricks());
    }

    static float podHull(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> TestEntities.vehicle(
                                server.getPlayerList().getPlayers().getFirst(), PodEntity.class)
                        .hull());
    }

    static double podY(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> TestEntities.vehicle(
                                server.getPlayerList().getPlayers().getFirst())
                        .getY());
    }
}
