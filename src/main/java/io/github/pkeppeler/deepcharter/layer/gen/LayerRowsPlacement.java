package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * Moves a position to a row picked evenly from rows {@code min} to {@code max} above the floor of {@code layer}, both ends included. It
 * is a height range that follows the layer: a layer dimension's floor is its own bottom, and a campaign world's layer is a band of the
 * level ({@link LayerChain#layerBand}), where a vanilla {@code above_bottom} would resolve against the bottom of the whole world and an
 * absolute Y would hold only in one of the two (#743).
 */
public record LayerRowsPlacement(int layer, int min, int max) implements PlacementModifier {
    public static final MapCodec<LayerRowsPlacement> CODEC = RecordCodecBuilder.<LayerRowsPlacement>mapCodec(instance ->
                    instance.group(
                                    Codec.intRange(1, Integer.MAX_VALUE)
                                            .fieldOf("layer")
                                            .forGetter(LayerRowsPlacement::layer),
                                    Codec.intRange(0, Integer.MAX_VALUE)
                                            .fieldOf("min")
                                            .forGetter(LayerRowsPlacement::min),
                                    Codec.intRange(0, Integer.MAX_VALUE)
                                            .fieldOf("max")
                                            .forGetter(LayerRowsPlacement::max))
                            .apply(instance, LayerRowsPlacement::new))
            .validate(rows -> rows.min <= rows.max
                    ? DataResult.success(rows)
                    : DataResult.error(() -> "min " + rows.min + " is above max " + rows.max));

    public static void register() {
        Registry.register(
                BuiltInRegistries.PLACEMENT_MODIFIER_TYPE,
                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "layer_rows"),
                CODEC);
    }

    @Override
    public void modify(PlacementContext context, RandomSource random, BlockPos pos, Consumer<BlockPos> output) {
        int floor = LayerChain.layerBand(context.generator(), context.getLevel(), layer, "A layer_rows placement")
                .minY();
        output.accept(pos.atY(floor + random.nextIntBetweenInclusive(min, max)));
    }

    @Override
    public MapCodec<LayerRowsPlacement> codec() {
        return CODEC;
    }
}
