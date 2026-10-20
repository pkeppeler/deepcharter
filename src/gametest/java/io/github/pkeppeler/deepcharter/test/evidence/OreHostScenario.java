package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "ore-host" for #549 (ADR 0055): every ore in the rock of its layer. In each of layers 1 and 2 a sealed room has a
 * south wall of the layer's own rock with the seven ores set in it, two high, lit by a pod lamp. The stills, per layer: the wall
 * from a distance, and a pair of ores close up. The recording pans along the wall in layer 1, then in layer 2.
 */
public class OreHostScenario extends EvidenceScenario {
    private static final BlockPos ROOM = new BlockPos(2700, 100, 2700);
    private static final int HALF_WIDTH = 6;
    /** A light as bright as a pod lamp gets, two blocks from the wall, so the rock reads in a still. */
    private static final int LAMP = 15;
    /** Frames of the pan along a wall, two game ticks apart. */
    private static final int PAN_FRAMES = 40;

    private static final int PAN_TICKS = 2;

    @Override
    protected String name() {
        return "ore-host";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        TextureTestWorld.open(context, still -> screenshot(context, still), world -> {
            List<Integer> layers = List.of(1, 2);
            for (int layer : layers) {
                build(world, layer);
            }
            for (int layer : layers) {
                stills(world, layer);
            }
            for (int layer : layers) {
                pan(context, world, layer);
            }
        });
    }

    /** The wall: the ores in two rows, a plain block between every two in a row, in the order of {@link OreType}. */
    private static void build(TextureTestWorld world, int layer) {
        Map<BlockPos, Block> blocks = new HashMap<>();
        OreType[] ores = OreType.values();
        for (int i = 0; i < ores.length; i++) {
            blocks.put(new BlockPos(-6 + 2 * i, i % 2 == 0 ? 0 : 2, 0), OreBlocks.of(ores[i]));
        }
        world.serverDo(server -> {
            ServerLevel level = ClientLight.serverLevel(server, LayerChain.dimension(layer));
            TextureTestWorld.buildWall(level, ROOM, HALF_WIDTH, blocks);
            level.setBlock(ROOM.offset(0, 2, 1), TextureTestWorld.lamp(LAMP), Block.UPDATE_ALL);
        });
    }

    private void stills(TextureTestWorld world, int layer) {
        Vec3 wall = Vec3.atBottomCenterOf(ROOM).add(0, 2, TextureTestWorld.WALL);
        world.view(layer, wall.add(0, 0.1, -8), wall, 60);
        world.still("layer-" + layer + "-ore-range");
        // The first two ores of the row: ironium (down) and bronzium (up), the cheap clumps.
        Vec3 pair = wall.add(-5, -0.5, 0);
        world.view(layer, pair.add(0, 0, -2.6), pair, 20);
        world.still("layer-" + layer + "-ore-close");
    }

    private void pan(ClientGameTestContext context, TextureTestWorld world, int layer) {
        Vec3 wall = Vec3.atBottomCenterOf(ROOM).add(0, 1.6, TextureTestWorld.WALL);
        for (int i = 0; i < PAN_FRAMES; i++) {
            double x = -6.0 + 12.0 * i / (PAN_FRAMES - 1);
            world.view(layer, wall.add(x, 0, -3.2), wall.add(x, 0, 0), PAN_TICKS);
            frame(context);
        }
    }
}
