package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.layer.Overgrowth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * A test or scenario's way into the Fungal Hollows' rule (#974) with no hollows in the world: {@link #BIOME} is a biome
 * of the test data that is the only member of the tag {@code deepcharter:fungal_hollows} there, and {@link #cut} cuts a
 * bore the way a drill does.
 */
public final class Hollows {
    /** The test data's one member of the tag (under {@code src/gametest/resources/data}). */
    public static final Identifier BIOME = Identifier.fromNamespaceAndPath("deepcharter-test", "hollows");

    private Hollows() {}

    /**
     * Puts the box between the corners (both inclusive, any order) in {@code biome} with {@code /fillbiome}, and fails
     * if it did not take: the command reports a failure only in the log. Biomes are stored in 4-block cells, so the box
     * is read at both corners.
     */
    public static void fill(ServerLevel level, BlockPos a, BlockPos b, Identifier biome) {
        CommandSourceStack source =
                level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
        level.getServer()
                .getCommands()
                .performPrefixedCommand(
                        source,
                        "fillbiome %d %d %d %d %d %d %s"
                                .formatted(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ(), biome));
        for (BlockPos at : new BlockPos[] {a, b}) {
            if (!stored(level, at).unwrapKey().orElseThrow().identifier().equals(biome)) {
                throw new IllegalStateException("/fillbiome did not put " + at + " in " + biome + "; it is in "
                        + stored(level, at).unwrapKey());
            }
        }
    }

    /** The biome stored for the cell, as the growth reads it. */
    private static Holder<Biome> stored(ServerLevel level, BlockPos pos) {
        return level.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
    }

    /** Takes the cell out of the rock and marks it a bore, as the pod drill does ({@code PodDrill}). */
    public static void cut(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Overgrowth.markBore(level, pos);
    }
}
