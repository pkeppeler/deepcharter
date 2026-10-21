package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.CompanyRockBlock;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "company-rock" (#535, docs/design/company-rock-concepts.md): the shipped Company Rock, in layers 1 and 2, shot in the places a
 * player meets it. Every room is sealed in the layer's rock (granite and tuff in the Grain test pack), so the void is never in a frame. Each still is
 * {@code l<layer>-<place>}: {@code l2-lava}. The places are those of the concept round (#503), so a still compares with that round's {@code now-*} stills.
 */
public class CompanyRockScenario extends EvidenceScenario {
    /** Where the rooms stand in each layer's dimension, on a chunk boundary, and the box of whole chunks of rock round all of them (a chunk the box covers in part never reached the client in two runs of three). */
    private static final BlockPos ORIGIN = new BlockPos(3616, LIVE_GAUGES_FLOOR_Y, 3616);

    private static final BlockPos ROCK_LOW = new BlockPos(-16, -3, -16);
    private static final BlockPos ROCK_HIGH = new BlockPos(63, 12, 15);
    /** Company Rock, in blocks from the origin: a patch in the wall of each of the four rooms (bore, cave, lava, hall), as the worldgen scatters it. */
    private static final List<BlockPos> PATCHES = List.of(
            new BlockPos(3, 5, -1),
            new BlockPos(4, 5, -1),
            new BlockPos(4, 6, -1),
            new BlockPos(2, 4, -1),
            new BlockPos(19, 3, -1),
            new BlockPos(20, 3, -1),
            new BlockPos(20, 4, -1),
            new BlockPos(18, 2, -1),
            new BlockPos(16, 3, 8),
            new BlockPos(39, 1, -1),
            new BlockPos(40, 1, -1),
            new BlockPos(40, 2, -1),
            new BlockPos(38, 2, -1),
            new BlockPos(53, 2, -1),
            new BlockPos(54, 2, -1),
            new BlockPos(54, 3, -1),
            new BlockPos(56, 3, -1),
            new BlockPos(52, 1, -1));

    private static final int LAMP = 12;
    private static final int SETTLE_TICKS = 20;
    /** Longer than the default 120 s: the first still waits for the layer's chunks to generate on a loaded machine. */
    private static final int SETTLE_LIMIT_SECONDS = 300;
    /** The render distance, in chunks, while the scenario runs: fewer tall-world chunks to generate round the rooms. Every room is sealed in rock, so no still shows it. */
    private static final int RENDER_DISTANCE = 2;
    /** The first frame after the player mounts is tinted, so the pod still waits for the mount's fade. */
    private static final int MOUNT_TICKS = 80;

    /** A view: its still suffix, the eye and the target in blocks from the origin. */
    private record View(String place, Vec3 eye, Vec3 target) {}

    private static final List<View> VIEWS = List.of(
            new View("bore", new Vec3(4.5, 5.6, 2.6), new Vec3(3.6, 5.4, -1)),
            new View("close", new Vec3(3.9, 5.4, 1.3), new Vec3(3.6, 5.4, -1)),
            new View("cave", new Vec3(19.5, 3.0, 5.5), new Vec3(19.0, 3.0, -1)),
            new View("lava", new Vec3(40.0, 2.7, 6.5), new Vec3(39.4, 1.8, -1)));
    /** The pod: it stands at the south end of the hall and looks south at the patch on the far wall. */
    private static final Vec3 SEAT_POD = new Vec3(51.5, 1, 5.5);

    private static final Vec3 SEAT_TARGET = new Vec3(53.5, 2.6, -1);

    @Override
    protected String name() {
        return "company-rock";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        int renderDistance = context.computeOnClient(
                client -> client.options.renderDistance().get());
        TextureTestWorld.open(context, shot -> screenshot(context, shot), world -> {
            world.settleLimit(SETTLE_LIMIT_SECONDS);
            context.runOnClient(client -> client.options.renderDistance().set(RENDER_DISTANCE));
            ClientPacks.enable(context, TestPacks.LAYER_CONCEPT_GRAIN);
            try {
                for (int layer : List.of(1, 2)) {
                    world.serverDo(server -> build(TestLayers.level(server, layer), layer));
                }
                for (int layer : List.of(1, 2)) {
                    world.serverDo(server -> place(TestLayers.level(server, layer), layer));
                    shoot(context, world, layer);
                }
                frame(context);
            } finally {
                ClientPacks.disable(context, TestPacks.LAYER_CONCEPT_GRAIN);
                context.runOnClient(client -> client.options.renderDistance().set(renderDistance));
            }
        });
    }

    private void shoot(ClientGameTestContext context, TextureTestWorld world, int layer) {
        for (View view : VIEWS) {
            world.view(layer, absolute(view.eye()), absolute(view.target()), SETTLE_TICKS);
            world.still(still(layer, view.place()));
        }
        world.serverDo(server -> mount(TestLayers.level(server, layer)));
        context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        ClientWait.until(
                context,
                "the player in the pod in layer " + layer,
                client -> client.player != null
                        && client.player.getVehicle() instanceof PodEntity
                        && TestLayers.isIn(ClientLight.clientLevel(client), layer));
        context.waitTicks(MOUNT_TICKS);
        world.still(still(layer, "pod"));
        context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        world.serverDo(CompanyRockScenario::dismount);
        ClientWait.until(
                context,
                "the player out of the pod",
                client -> client.player != null && client.player.getVehicle() == null);
    }

    private static String still(int layer, String place) {
        return "l%d-%s".formatted(layer, place);
    }

    private static Vec3 absolute(Vec3 local) {
        return local.add(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ());
    }

    private static BlockPos absolute(BlockPos local) {
        return local.offset(ORIGIN);
    }

    /**
     * Builds the five rooms of a layer in a box of its rock: a bore (5 x 3, tall), a cave (an irregular room with a mounded floor and a hanging
     * ceiling), a room with a pit of lava along its wall, and the hall the pod sits in. Everything solid is the layer's rock, so every wall is rock.
     */
    private static void build(ServerLevel level, int layer) {
        Block rock = layer == 1 ? Blocks.GRANITE : Blocks.TUFF;
        BlockPos low = absolute(ROCK_LOW);
        BlockPos high = absolute(ROCK_HIGH);
        for (int cx = low.getX() >> 4; cx <= high.getX() >> 4; cx++) {
            for (int cz = low.getZ() >> 4; cz <= high.getZ() >> 4; cz++) {
                // force-ok: an evidence scenario has no GameTestHelper to release through; its world is discarded when
                // the run ends
                level.setChunkForced(cx, cz, true);
                level.getChunk(cx, cz);
            }
        }
        RoomCarver.carve(level, low, high, rock.defaultBlockState());
        air(level, 2, 6, 1, 11, 0, 2);
        air(level, 12, 28, 1, 6, 0, 13);
        air(level, 34, 45, 1, 5, 0, 9);
        air(level, 50, 58, 1, 5, 0, 10);
        // The cave's floor rises in mounds and its roof hangs in stubs, in the rock's own block.
        for (int[] mound : new int[][] {{14, 4}, {16, 8}, {22, 6}, {25, 10}, {13, 11}, {24, 2}}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int height = dx == 0 && dz == 0 ? 2 : 1;
                    for (int y = 1; y <= height; y++) {
                        level.setBlock(
                                absolute(new BlockPos(mound[0] + dx, y, mound[1] + dz)),
                                rock.defaultBlockState(),
                                Block.UPDATE_ALL);
                    }
                }
            }
        }
        for (int[] stub : new int[][] {{15, 3}, {21, 5}, {26, 8}, {18, 10}, {13, 7}}) {
            for (int y = 5; y <= 6; y++) {
                level.setBlock(absolute(new BlockPos(stub[0], y, stub[1])), rock.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        // The pit of lava: its sides, bottom and the floor round it are rock, so the sources stay where they are put.
        for (int x = 38; x <= 41; x++) {
            for (int z = 0; z <= 1; z++) {
                level.setBlock(absolute(new BlockPos(x, 0, z)), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        for (View view : VIEWS) {
            lamp(level, view.eye());
        }
        lamp(level, SEAT_POD.add(0, 2, -1));
    }

    private static void air(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2) {
        RoomCarver.carve(
                level,
                absolute(new BlockPos(x1, y1, z1)),
                absolute(new BlockPos(x2, y2, z2)),
                Blocks.AIR.defaultBlockState());
    }

    private static void lamp(ServerLevel level, Vec3 eye) {
        level.setBlock(BlockPos.containing(absolute(eye)), TextureTestWorld.lamp(LAMP), Block.UPDATE_ALL);
    }

    /** Sets every patch of Company Rock of a layer, in the rock of that layer as the worldgen sets it (ADR 0056), so layer 2's joints glow. */
    private static void place(ServerLevel level, int layer) {
        BlockState rock = ((CompanyRockBlock) HazardBlocks.COMPANY_ROCK)
                .in((layer == 1 ? LayerBlocks.PACKED_SHALE : LayerBlocks.GREEN_SHALE).defaultBlockState());
        for (BlockPos at : PATCHES) {
            level.setBlock(absolute(at), rock, Block.UPDATE_ALL);
        }
    }

    /** Seats the player in a pod at the hall's south end, facing the far wall. */
    private static void mount(ServerLevel level) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayers().getFirst();
        Vec3 at = absolute(SEAT_POD);
        Vec3 target = absolute(SEAT_TARGET);
        player.teleportTo(level, at.x, at.y, at.z, Set.of(), 180.0f, 0.0f, true);
        player.setNoGravity(false);
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(at.x, at.y, at.z);
        pod.setYRot(180.0f);
        pod.setFuel(100f);
        level.addFreshEntity(pod);
        if (!player.startRiding(pod)) {
            throw new AssertionError("the player could not mount the pod");
        }
        double pitch = Math.toDegrees(Math.atan2(at.y + 1.4 - target.y, Math.hypot(target.x - at.x, target.z - at.z)));
        player.setXRot((float) pitch);
    }

    private static void dismount(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        if (player.getVehicle() instanceof PodEntity pod) {
            player.stopRiding();
            pod.discard();
        }
        player.setNoGravity(true);
    }
}
