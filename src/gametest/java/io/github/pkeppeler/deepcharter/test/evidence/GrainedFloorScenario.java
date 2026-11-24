package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.layer.BlockReadoutHud;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "grained-floor" (#1022): the floor of the last layer is grained crust, as the seed's draw makes it
 * in the campaign world, and a grained floor is as shut as a breach crust.
 *
 * <ol>
 *   <li>A pod sits on the grained floor and the pilot holds sprint: the drill is refused and the floor stays.
 *   <li>On foot, with a netherite pick, the readout of the grained crust says that only a pod drill breaks it, and
 *       that the pick would burn out.
 * </ol>
 */
public class GrainedFloorScenario extends EvidenceScenario {
    private static final int LAST = CampaignBands.lastLayer();
    private static final int X = 5600;
    private static final int Z = 5600;
    private static final int FLOOR_Y = TestLayers.worldY(LAST, LayerTuning.DEFAULT.crustThickness());
    private static final int HALF = 6;
    private static final int HEIGHT = 8;
    private static final int TICKS_PER_FRAME = 10;
    private static final int PUSH_FRAMES = 14;
    private static final float LOOK_DOWN = 40f;
    private static final float FOOT_YAW = 180f;

    @Override
    protected String name() {
        return "grained-floor";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel level = TestLayers.level(server, LAST);
                build(level);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.setPermanentlyInvulnerable(true);
                player.teleportTo(level, X, FLOOR_Y, Z, Set.of(), 0, LOOK_DOWN, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, level);
                pod.setPos(X, FLOOR_Y, Z);
                pod.setFuel(100f);
                level.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(
                    context,
                    "the player riding a pod in the last layer",
                    client -> client.player != null
                            && client.player.getVehicle() instanceof PodEntity
                            && TestLayers.clientIsIn(client, LAST));
            context.runOnClient(client -> {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                ClientSession.player(client).setXRot(LOOK_DOWN);
            });
            // The directive toast of the join fades first, and the camera settles behind the pod.
            context.waitTicks(100);
            frame(context);
            screenshot(context, "grained-floor-pod");

            // The pilot only holds sprint, which drills what is under the pod; the floor must hold.
            context.getInput().holdKey(options -> options.keySprint);
            for (int i = 0; i < PUSH_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
                if (i == PUSH_FRAMES - 2) {
                    screenshot(context, "grained-floor-refused");
                }
            }
            context.getInput().releaseKey(options -> options.keySprint);
            requireFloorWhole(singleplayer);

            BlockPos crust = onFoot(context, singleplayer);
            context.getInput().lookAt(crust);
            ClientWait.until(
                    context,
                    "the readout of the grained crust, with the netherite pick in hand",
                    client -> BlockReadoutHud.shown()
                            .filter(readout -> readout.name().equals(LayerBlocks.GRAINED_CRUST.getName()))
                            .isPresent());
            for (int i = 0; i < 6; i++) {
                context.waitTicks(TICKS_PER_FRAME / 2); // tick-wait: the readout's fade-in is a few ticks
                frame(context);
            }
            screenshot(context, "grained-floor-readout");
        }
    }

    /** The player leaves the pod for a stone pad with a netherite pick; returns a crust block to look at. */
    private BlockPos onFoot(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        BlockPos crust = new BlockPos(X + 2, FLOOR_Y - 1, Z + 2);
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = TestLayers.level(server, LAST);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.vehicle(player, PodEntity.class);
            player.stopRiding();
            pod.discard();
            // A pad of stone one block up, so that the player stands off the grain, which squeezes whoever stops on it.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(
                            new BlockPos(X + dx, FLOOR_Y, Z + dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
            player.teleportTo(level, X, FLOOR_Y + 1, Z, Set.of(), FOOT_YAW, 30f, true);
        });
        context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        ClientWait.until(
                context,
                "the player out of the pod",
                client -> client.player != null && client.player.getVehicle() == null);
        context.waitTicks(10);
        return crust;
    }

    private static void requireFloorWhole(TestSingleplayerContext singleplayer) {
        boolean whole = singleplayer.getServer().computeOnServer(server -> {
            ServerLevel level = TestLayers.level(server, LAST);
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            return !pod.drilling()
                    && level.getBlockState(new BlockPos(X, FLOOR_Y - 1, Z)).is(LayerBlocks.GRAINED_CRUST);
        });
        if (!whole) {
            throw new AssertionError("the pod drilled, or the grained floor under it is gone");
        }
    }

    /** The last layer's floor rows as grained crust, an open room over them, and lamps, sealed in stone. */
    private static void build(ServerLevel level) {
        RoomCarver.carve(
                level,
                X - HALF - 1,
                X + HALF + 1,
                TestLayers.worldY(LAST, 0),
                FLOOR_Y - 1,
                Z - HALF - 1,
                Z + HALF + 1,
                LayerBlocks.GRAINED_CRUST);
        RoomCarver.carve(
                level,
                new BlockPos(X - HALF - 1, FLOOR_Y, Z - HALF - 1),
                new BlockPos(X + HALF + 1, FLOOR_Y + HEIGHT + 1, Z + HALF + 1),
                Blocks.STONE.defaultBlockState(),
                Block.UPDATE_ALL);
        RoomCarver.carve(
                level,
                new BlockPos(X - HALF, FLOOR_Y, Z - HALF),
                new BlockPos(X + HALF, FLOOR_Y + HEIGHT, Z + HALF),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        for (BlockPos lamp : new BlockPos[] {
            new BlockPos(X - HALF, FLOOR_Y + 3, Z),
            new BlockPos(X + HALF, FLOOR_Y + 3, Z),
            new BlockPos(X, FLOOR_Y + 3, Z - HALF),
            new BlockPos(X, FLOOR_Y + 3, Z + HALF),
            new BlockPos(X + 3, FLOOR_Y + HEIGHT, Z - 3)
        }) {
            level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_ALL);
        }
    }
}
