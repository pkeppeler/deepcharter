package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.CompanyRockBlock;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "company-rock-blast" (#539, ADR 0056): a slab of Company rock under a pod, with a trench in front of it so that its face can be
 * seen, in the real rock of layer 1 and then of layer 2. The pilot sets a blasting charge, climbs out and watches: the joints widen in two steps,
 * and the rock goes. The Company rock beside the slab is not under the charge and stays as it was, for contrast. Stills {@code l<layer>-rest},
 * {@code l<layer>-step1}, {@code l<layer>-step2} and {@code l<layer>-gone}; the frames of the layer 2 run make the GIF.
 */
public class CompanyRockBlastScenario extends EvidenceScenario {
    private static final int X = 5600;
    private static final int Z = 5100;
    // fixed-pos: a trench and slab the scenario builds in layer 1 and layer 2 at this height, with the HUD hidden
    private static final int FLOOR_Y = 40;
    private static final int TICKS_PER_FRAME = 2;
    private static final int SETTLE_TICKS = 40;
    private static final int LAMP = 12;
    /** Where the camera stands: on the trench floor, four blocks from the slab's face and level with its middle. */
    private static final Vec3 EYE = new Vec3(X, FLOOR_Y - 2, Z + 4.5);

    private static BlockPos slabCell() {
        return new BlockPos(X, FLOOR_Y - 1, Z);
    }

    @Override
    protected String name() {
        return "company-rock-blast";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        ClientChecks.withHudHidden(context, () -> {
            try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
                context.waitTicks(40);
                for (int layer = 1; layer <= 2; layer++) {
                    blast(context, singleplayer, layer);
                }
            }
        });
    }

    private void blast(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer) {
        // Only the layer 2 run is a recording: the GIF is the layer that glows.
        boolean recording = layer == 2;
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = ClientLight.serverLevel(server, LayerChain.dimension(layer));
            build(level, layer);
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            watch(level, player);
        });
        ClientWait.until(
                context,
                "the slab of Company rock in layer " + layer,
                client -> client.player != null
                        && ClientLight.clientLevel(client).dimension().equals(LayerChain.dimension(layer))
                        && ClientLight.clientLevel(client)
                                .getBlockState(slabCell())
                                .is(HazardBlocks.COMPANY_ROCK));
        context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        context.waitTicks(SETTLE_TICKS);
        screenshot(context, "l%d-rest".formatted(layer));
        for (int i = 0; i < 6 && recording; i++) {
            frame(context);
            context.waitTicks(TICKS_PER_FRAME);
        }

        // Seat the player, set the charge from the seat, climb out and go back to the trench.
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = ClientLight.serverLevel(server, LayerChain.dimension(layer));
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(X, FLOOR_Y, Z);
            level.addFreshEntity(pod);
            player.teleportTo(level, X, FLOOR_Y, Z, Set.of(), 0, 0, true);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the player could not mount the pod");
            }
        });
        ClientWait.until(
                context,
                "the pod resting on its slab",
                client ->
                        client.player != null && client.player.getVehicle() instanceof PodEntity pod && pod.onGround());
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = ClientLight.serverLevel(server, LayerChain.dimension(layer));
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory()
                    .setItem(
                            player.getInventory().getSelectedSlot(),
                            new ItemStack(RepairRegistry.item(Consumable.BLASTING_CHARGE)));
            if (!player.gameMode
                    .useItem(
                            player,
                            player.level(),
                            player.getItemInHand(InteractionHand.MAIN_HAND),
                            InteractionHand.MAIN_HAND)
                    .consumesAction()) {
                throw new AssertionError("the charge should be set from the seat");
            }
            player.stopRiding();
            watch(level, player);
        });
        for (int step = 1; step <= CompanyRockBlock.MAX_CRACK; step++) {
            int wanted = step;
            while (crack(context) < wanted) {
                if (recording) {
                    frame(context);
                }
                context.waitTicks(TICKS_PER_FRAME);
                if (crack(context) < 0) {
                    throw new AssertionError("the slab was cleared before step " + wanted);
                }
            }
            // A few ticks for the new joints to be meshed and drawn.
            context.waitTicks(TICKS_PER_FRAME * 2);
            screenshot(context, "l%d-step%d".formatted(layer, step));
        }
        while (crack(context) >= 0) {
            if (recording) {
                frame(context);
            }
            context.waitTicks(TICKS_PER_FRAME);
        }
        for (int i = 0; i < 8; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            if (recording) {
                frame(context);
            }
        }
        screenshot(context, "l%d-gone".formatted(layer));
    }

    /** The slab's crack on the client, or -1 once the rock is gone from it. */
    private static int crack(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            BlockState state = ClientLight.clientLevel(client).getBlockState(slabCell());
            return state.is(HazardBlocks.COMPANY_ROCK) ? state.getValue(CompanyRockBlock.CRACK) : -1;
        });
    }

    /** Puts the player on the trench floor, looking north at the slab's face. */
    private static void watch(ServerLevel level, ServerPlayer player) {
        player.teleportTo(level, EYE.x, EYE.y, EYE.z, Set.of(), 180f, 0f, true);
    }

    /**
     * A sealed room of the layer's own rock, a 2 x 2 slab of Company rock two rows deep with a column of Company rock beside it on each side (not
     * under the charge), and a trench in front of the slab, so that the camera looks at the faces of the slab and of its neighbours.
     */
    private static void build(ServerLevel level, int layer) {
        Block ground = layer == 1 ? LayerBlocks.PACKED_SHALE : LayerBlocks.GREEN_SHALE;
        RoomCarver.carve(level, X - 9, X + 9, FLOOR_Y - 9, FLOOR_Y + 10, Z - 13, Z + 13, ground);
        RoomCarver.carve(
                level,
                new BlockPos(X - 8, FLOOR_Y, Z - 12),
                new BlockPos(X + 8, FLOOR_Y + 8, Z + 12),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        BlockState rock = ((CompanyRockBlock) HazardBlocks.COMPANY_ROCK).in(ground.defaultBlockState());
        for (int dx = -3; dx <= 2; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                for (int dy = -2; dy <= -1; dy++) {
                    level.setBlock(new BlockPos(X + dx, FLOOR_Y + dy, Z + dz), rock, Block.UPDATE_ALL);
                }
            }
        }
        // The trench: the rows of the slab, from its face out to the camera.
        RoomCarver.carve(
                level,
                new BlockPos(X - 4, FLOOR_Y - 2, Z + 1),
                new BlockPos(X + 3, FLOOR_Y - 1, Z + 8),
                Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        // Two pairs of lamps, one over each half of the slab's face, so that the face is lit evenly from end to end.
        for (int lampX : new int[] {X - 2, X + 1}) {
            level.setBlock(new BlockPos(lampX, FLOOR_Y, Z + 3), TextureTestWorld.lamp(LAMP), Block.UPDATE_ALL);
            level.setBlock(new BlockPos(lampX, FLOOR_Y - 2, Z + 2), TextureTestWorld.lamp(LAMP), Block.UPDATE_ALL);
        }
    }
}
