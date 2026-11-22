package io.github.pkeppeler.deepcharter.creature;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Stub: filled below. */
public final class DrawSources {
    private DrawSources() {}

    public static void addFlare(ServerLevel level, BlockPos pos, double radius, long untilTick) {}

    public static boolean litByFlare(Level level, Vec3 at) {
        return false;
    }

    public static void addDecoy(ServerLevel level, Vec3 pos, double pullBlocks, long untilTick) {}

    public static Vec3 noiseTarget(ServerLevel level, Vec3 from, Vec3 drillAt) {
        return drillAt;
    }

    public static void clear() {}
}
