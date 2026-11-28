package io.github.pkeppeler.deepcharter.client.layer;

import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.RockTint;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import io.github.pkeppeler.deepcharter.ore.OreType;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The block colours of the layers' rock (#1551): the rock's models draw a neutral grey texture under tint index 0, and
 * this multiplies it by {@link RockTint}'s colour for the block's height, so the rock takes each zone's colour and
 * gives way from one to the next without a block between. An ore's host layer carries the same tint index, so an ore
 * takes the colour of the wall round it; the item of each carries its home colour in its item definition.
 */
final class RockTints {
    /** The alpha byte of an opaque ARGB colour. */
    private static final int OPAQUE = 255 << 24;
    /** The tint of a rock block, which has no state that changes it. */
    private static final BlockTintSource ROCK = new Strata(Set.of());
    /** The tint of an ore, which follows its host. */
    private static final BlockTintSource ORE = new Strata(Set.<Property<?>>of(OreBlock.HOST));

    private RockTints() {}

    static void init() {
        BlockColorRegistry.register(
                List.of(ROCK),
                LayerBlocks.HARDPAN,
                LayerBlocks.PACKED_SHALE,
                LayerBlocks.BLACK_SHALE,
                LayerBlocks.GREEN_SHALE,
                LayerBlocks.BASALT_CORE);
        Block[] ores = Arrays.stream(OreType.values())
                .map(type -> BuiltInRegistries.BLOCK.getValue(type.blockId()))
                .toArray(Block[]::new);
        BlockColorRegistry.register(List.of(ORE), ores);
    }

    private record Strata(Set<Property<?>> relevant) implements BlockTintSource {
        @Override
        public int color(BlockState state) {
            return opaque(RockTint.homeOf(state));
        }

        @Override
        public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
            return opaque(RockTint.of(state, pos.getY()));
        }

        @Override
        public Set<Property<?>> relevantProperties() {
            return relevant;
        }

        private static int opaque(int rgb) {
            return OPAQUE | rgb;
        }
    }
}
