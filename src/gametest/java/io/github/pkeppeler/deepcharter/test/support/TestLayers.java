package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The one place a test or evidence scenario turns a layer and a local Y into a level and a world position. A layer is its
 * number ({@link LayerChain#SURFACE} for the surface); a local Y is a height inside that layer. Tests never name
 * {@code LayerChain.dimension} or a layer's Y themselves ({@code checkFixedPosGate}), so a change in how layers are laid out
 * edits this class only. {@code SURFACE} positions are in the flat test overworld; GameTests reach it through the helper's level.
 */
public final class TestLayers {
    /** The surface, the overworld of the chain. */
    public static final int SURFACE = LayerChain.SURFACE;

    private TestLayers() {}

    /** The level key of {@code layer}, for comparing with a {@code level.dimension()} or passing to a teleport. */
    public static ResourceKey<Level> dimension(int layer) {
        return LayerChain.dimension(layer);
    }

    /** The server's level for {@code layer}; fails, naming the dimension, when the server has none. */
    public static ServerLevel level(MinecraftServer server, int layer) {
        return ClientLight.serverLevel(server, dimension(layer));
    }

    /** The level for {@code layer} of the server running {@code helper}'s test. */
    public static ServerLevel level(GameTestHelper helper, int layer) {
        return level(helper.getLevel().getServer(), layer);
    }

    /** True when {@code level} is the level of {@code layer}. */
    public static boolean isIn(Level level, int layer) {
        return level.dimension().equals(dimension(layer));
    }

    /** The world Y of {@code localY} in {@code layer}. */
    public static int worldY(int layer, int localY) {
        return localY;
    }

    /** The world Y of a fractional {@code localY} in {@code layer}. */
    public static double worldY(int layer, double localY) {
        return localY;
    }

    /** The world position of ({@code x}, {@code localY}, {@code z}) in {@code layer}. */
    public static BlockPos pos(int layer, int x, int localY, int z) {
        return new BlockPos(x, worldY(layer, localY), z);
    }

    /** The world position, with fractions, of ({@code x}, {@code localY}, {@code z}) in {@code layer}. */
    public static Vec3 vec(int layer, double x, double localY, double z) {
        return new Vec3(x, worldY(layer, localY), z);
    }
}
