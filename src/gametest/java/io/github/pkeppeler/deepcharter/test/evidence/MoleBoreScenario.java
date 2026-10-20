package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "mole-bore" for #400: the real player pilots the new, taller Mole in a lit stone room. It drills three slabs down
 * through the floor (a 2 x 2 hole), then, from the other end of the room, drills into a stone wall (a 2 wide, 3 tall hole, the Mole's
 * height). The camera is third person, behind the pod. The pilot only holds sprint, then forward.
 */
public class MoleBoreScenario extends EvidenceScenario {
    private static final int X = 4200;
    private static final int Z = 4200;
    private static final int FLOOR_Y = 60;
    private static final int ROOM = 8;
    private static final int ROOM_HEIGHT = 9;
    private static final int DEPTH = 3;
    private static final int WALL_X = X + 2;
    private static final int TICKS_PER_FRAME = 6;
    private static final int MAX_TICKS = 1800;
    private static final float LOOK_DOWN = 38f;
    private static final float LOOK_LEVEL = 14f;
    private static final float EAST = -90f;
    private static final int END_FRAMES = 8;

    @Override
    protected String name() {
        return "mole-bore";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        ClientChecks.withHudHidden(context, () -> runHidden(context));
    }

    private void runHidden(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = ClientLight.serverLevel(server, LayerChain.dimension(1));
                buildRoom(one);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.teleportTo(one, X - 6, FLOOR_Y, Z, Set.of(), 0, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(X - 6, FLOOR_Y, Z);
                pod.setFuel(100f);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the player riding a pod in layer 1",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && ClientLight.clientLevel(client).dimension().equals(LayerChain.dimension(1)));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            context.waitTicks(20);
            frame(context);

            // Down: sprint until the pod's feet are DEPTH slabs under the floor.
            context.getInput().holdKey(options -> options.keySprint);
            int ticks = 0;
            boolean shot = false;
            while (podY(context) > FLOOR_Y - DEPTH + 0.2 && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
                // One slab down, the shaft is still open to the camera above it.
                if (!shot && podY(context) < FLOOR_Y - 1 + 0.2) {
                    screenshot(context, "mole-bored-down");
                    shot = true;
                }
            }
            context.getInput().releaseKey(options -> options.keySprint);
            if (podY(context) > FLOOR_Y - DEPTH + 0.2) {
                throw new AssertionError(
                        "the Mole did not drill " + DEPTH + " slabs down within " + MAX_TICKS + " ticks");
            }
            for (int i = 0; i < END_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }

            // Sideways: the pod stands at the west end of the room facing the wall, and drills into it.
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity pod = TestEntities.vehicle(player, PodEntity.class);
                pod.setFuel(100f);
                pod.teleportTo(
                        ClientLight.serverLevel(server, LayerChain.dimension(1)),
                        X - 2.5,
                        FLOOR_Y,
                        Z + 0.5,
                        Set.of(),
                        EAST,
                        0f,
                        true);
                player.setYRot(EAST);
            });
            context.runOnClient(client -> {
                ClientSession.player(client).setYRot(EAST);
                ClientSession.player(client).yRotO = EAST;
                ClientSession.player(client).setXRot(LOOK_LEVEL);
                ClientSession.player(client).xRotO = LOOK_LEVEL;
            });
            context.waitTicks(30);
            frame(context);
            context.getInput().holdKey(options -> options.keyUp);
            ticks = 0;
            while (podX(context) < WALL_X + DEPTH - 0.6 && ticks < MAX_TICKS) {
                context.waitTicks(TICKS_PER_FRAME);
                ticks += TICKS_PER_FRAME;
                frame(context);
            }
            context.getInput().releaseKey(options -> options.keyUp);
            if (podX(context) < WALL_X + DEPTH - 0.6) {
                throw new AssertionError(
                        "the Mole did not drill " + DEPTH + " slabs into the wall within " + MAX_TICKS + " ticks");
            }
            screenshot(context, "mole-bored-sideways");
            for (int i = 0; i < END_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
        }
    }

    private static double podY(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> TestEntities.vehicle(ClientSession.player(client)).getY());
    }

    private static double podX(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> TestEntities.vehicle(ClientSession.player(client)).getX());
    }

    /** A stone block with a room cut in it, a wall of stone at the east end, and glowstone in the rock round the room to light it. */
    private static void buildRoom(ServerLevel level) {
        RoomCarver.carve(
                level, X - ROOM, X + ROOM, FLOOR_Y - 12, FLOOR_Y + ROOM_HEIGHT + 1, Z - ROOM, Z + ROOM, Blocks.STONE);
        // Worldgen scatters lava through layer 1's rock, and lava beside the room would flow in, so carve it sealed.
        RoomCarver.carve(
                level,
                new BlockPos(X - ROOM + 1, FLOOR_Y, Z - ROOM + 1),
                new BlockPos(X + 1, FLOOR_Y + ROOM_HEIGHT, Z + ROOM - 1),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        for (int x = X - ROOM + 2; x <= X; x += 3) {
            level.setBlock(new BlockPos(x, FLOOR_Y + 4, Z - ROOM), Blocks.GLOWSTONE.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, FLOOR_Y + 4, Z + ROOM), Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
        for (int z = Z - ROOM + 2; z <= Z + ROOM - 2; z += 4) {
            level.setBlock(new BlockPos(X - ROOM, FLOOR_Y + 3, z), Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
        for (int y = FLOOR_Y - 2; y <= FLOOR_Y + 5; y += 3) {
            level.setBlock(new BlockPos(X - 6, y, Z - 3), Blocks.GLOWSTONE.defaultBlockState(), 3);
            level.setBlock(new BlockPos(X - 6, y, Z + 3), Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
    }
}
