package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import io.github.pkeppeler.deepcharter.creature.DrifterAlarm;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.LoudPods;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "drifters" (#973): a pilot sits in a pod that drills (only the drill's noise: the pod makes it but does not bore, so it stays
 * where it is and the camera keeps its view) at the west end of a dark hall in layer 1. At the far end a drifter glows. It drifts down the hall toward the drill, and the
 * stills show the glow far off, the glow near, the burst and the spore gas round the hull with the hull falling, and then the alarm: a second
 * drifter is struck, and the plain HUD's readout carries the alarm line. The pilot is shielded from the gas by the hull; the hull is what it eats.
 */
public class DrifterScenario extends EvidenceScenario {
    private static final int X = 5800;
    private static final int Z = 5800;
    private static final int FLOOR_Y = LAYER_ROOM_Y;
    private static final int HALL_LENGTH = 30;
    private static final int TICKS_PER_FRAME = 4;
    private static final int MAX_TICKS = 2400;
    private static final float FACING_EAST = -90f;
    private static final double START_BLOCKS = 24;
    private static final double NEAR_BLOCKS = 9;
    /** Ticks of frames shown in the gas, after the burst: the cloud hangs 160. */
    private static final int GAS_FRAMES = 20;

    @Override
    protected String name() {
        return "drifters";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            Drifter[] first = {null};
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                build(one);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, X, FLOOR_Y, Z, Set.of(), FACING_EAST, 0f, true);
            });
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, X + HALL_LENGTH / 2, Z, HALL_LENGTH / 2 + 4);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X, FLOOR_Y, Z);
                pod.setYRot(FACING_EAST);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
                first[0] = TestEntities.create(CreatureRegistry.DRIFTER, one);
                first[0].setPos(X + START_BLOCKS, FLOOR_Y + 0.5, Z);
                one.addFreshEntity(first[0]);
            });
            ClientWait.until(
                    context,
                    "the pilot seated in the pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.clientIsIn(client, 1));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                ClientSession.player(client).setXRot(0f);
            });
            ClientPacks.enable(context, TestPacks.COCKPIT_OFF);
            context.waitTicks(40);
            for (int i = 0; i < 4; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
            screenshot(context, "drifters-quiet");

            // The drill's noise starts: the drifter hears it 24 blocks off and drifts down the hall.
            singleplayer.getServer().runOnServer(server -> LoudPods.make(pilotPod(server)));
            int ticks = 0;
            boolean far = false;
            while (distance(singleplayer, first[0]) > NEAR_BLOCKS) {
                if (ticks >= MAX_TICKS) {
                    throw new AssertionError("the drifter did not come within " + NEAR_BLOCKS + " blocks");
                }
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
                if (!far && distance(singleplayer, first[0]) < START_BLOCKS - 4) {
                    screenshot(context, "drifters-glow-far");
                    far = true;
                }
            }
            screenshot(context, "drifters-glow-near");
            ticks = 0;
            while (!removed(singleplayer, first[0])) {
                if (ticks >= MAX_TICKS) {
                    throw new AssertionError("the drifter never burst on the drilling pod");
                }
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            frame(context);
            screenshot(context, "drifters-burst");
            float maxHull = singleplayer
                    .getServer()
                    .computeOnServer(server -> pilotPod(server).maxHull());
            for (int i = 0; i < GAS_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            float hull = singleplayer
                    .getServer()
                    .computeOnServer(server -> pilotPod(server).hull());
            if (!(hull < maxHull)) {
                throw new AssertionError("the spore gas did not eat the hull: " + hull + " of " + maxHull);
            }
            screenshot(context, "drifters-gas");

            // The alarm: the pilot pops a second drifter, 12 blocks off, and the readout says so.
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                Drifter second = TestEntities.create(CreatureRegistry.DRIFTER, one);
                second.setPos(new Vec3(X + 12, FLOOR_Y + 0.5, Z));
                second.setNoAi(true);
                one.addFreshEntity(second);
                second.hurtServer(one, one.damageSources().playerAttack(player), second.getMaxHealth());
            });
            ticks = 0;
            while (!context.computeOnClient(
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && PodStatusHud.drifterAlarmLine(pod).isPresent())) {
                if (ticks >= MAX_TICKS) {
                    throw new AssertionError("the readout never showed the alarm");
                }
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            for (int i = 0; i < GAS_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "drifters-alarm-line");
            singleplayer.getServer().runOnServer(server -> {
                LoudPods.quiet(pilotPod(server));
                if (!DrifterAlarm.isRinging(TestLayers.level(server, 1), FLOOR_Y)) {
                    throw new AssertionError("the alarm of the zone does not ring on the server");
                }
            });
        }
    }

    private static PodEntity pilotPod(MinecraftServer server) {
        return TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
    }

    private static double distance(TestSingleplayerContext singleplayer, Drifter drifter) {
        return singleplayer
                .getServer()
                .computeOnServer(
                        server -> drifter.position().distanceTo(pilotPod(server).position()));
    }

    private static boolean removed(TestSingleplayerContext singleplayer, Drifter drifter) {
        return singleplayer.getServer().computeOnServer(server -> drifter.isRemoved());
    }

    /** A dark hall of stone, 5 wide and 6 high, running east from the pod, in the layer's rock. */
    private static void build(ServerLevel level) {
        BlockPos low = new BlockPos(X - 4, FLOOR_Y - 4, Z - 4);
        BlockPos high = new BlockPos(X + HALL_LENGTH + 2, FLOOR_Y + 9, Z + 4);
        RoomCarver.carve(level, low, high, Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                level,
                new BlockPos(X - 2, FLOOR_Y, Z - 2),
                new BlockPos(X + HALL_LENGTH, FLOOR_Y + 5, Z + 2),
                Blocks.AIR.defaultBlockState());
    }
}
