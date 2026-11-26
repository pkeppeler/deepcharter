package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

/**
 * A density function that is its input in rows {@code minInclusive <= y < maxExclusive} and {@code outside} elsewhere
 * (#1367), as {@code range_choice} on {@code y} with a constant out of range, except that it never samples its input
 * outside the band: 26.3's {@code RangeChoiceFunction$Sampler.sampleVolume} samples both branches over the whole
 * volume, so a term gated by it costs every row of every band.
 *
 * <p>A volume is sampled as the rows of it in the band, a volume of its own, so an input that interpolates sees a
 * volume that starts at the band's bottom edge rather than the caller's. Its cells then start where they would have,
 * and its blocks come out the same, only when that edge is on its cell grid. Data that wraps an interpolated input
 * keeps {@code minInclusive} a multiple of the cell height. A stepped volume whose rows step over the bottom edge is
 * sampled whole and its outside rows overwritten, since its band rows would start off the edge.
 *
 * @param input         the value in the band
 * @param minInclusive  the band's lowest row
 * @param maxExclusive  the first row above the band
 * @param outside       the value outside the band
 */
public record YBandFunction(DensityFunction input, int minInclusive, int maxExclusive, float outside)
        implements DensityFunction {
    public static final MapCodec<YBandFunction> CODEC = RecordCodecBuilder.<YBandFunction>mapCodec(
                    instance -> instance.group(
                                    DensityFunction.CODEC.fieldOf("input").forGetter(YBandFunction::input),
                                    Codec.INT.fieldOf("min_inclusive").forGetter(YBandFunction::minInclusive),
                                    Codec.INT.fieldOf("max_exclusive").forGetter(YBandFunction::maxExclusive),
                                    Codec.FLOAT.fieldOf("outside").forGetter(YBandFunction::outside))
                            .apply(instance, YBandFunction::new))
            .validate(band -> band.minInclusive < band.maxExclusive
                    ? DataResult.success(band)
                    : DataResult.error(() -> "y_band is empty: " + band.minInclusive + " to " + band.maxExclusive));

    public static void register() {
        Registry.register(
                BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "y_band"),
                CODEC);
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new Sampler(input.compileSampler(context), minInclusive, maxExclusive, outside);
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        DensityFunction newInput = rule.rewrite(input);
        return newInput == input ? this : new YBandFunction(newInput, minInclusive, maxExclusive, outside);
    }

    @Override
    public Interval range() {
        return Interval.encapsulating(input.range(), Interval.ofExact(outside));
    }

    @Override
    public int domainAxes() {
        return input.domainAxes() | AXIS_Y;
    }

    @Override
    public MapCodec<YBandFunction> codec() {
        return CODEC;
    }

    private record Sampler(DensitySampler input, int minInclusive, int maxExclusive, float outside)
            implements DensitySampler {
        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            return y >= minInclusive && y < maxExclusive ? input.sampleValue(context, x, y, z) : outside;
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            int minY = volume.minBlockY();
            int stepY = volume.stepBlockY();
            int sizeY = volume.sizeY();
            // Rows first <= j < end are in the band.
            int first = minY >= minInclusive ? 0 : Math.ceilDiv(minInclusive - minY, stepY);
            int end = maxExclusive > minY ? Math.min(sizeY, Math.ceilDiv(maxExclusive - minY, stepY)) : 0;
            if (first >= end) {
                output.setRange(0, volume.size(), outside);
                return;
            }
            if (first == 0 && end == sizeY) {
                input.sampleVolume(context, output, volume);
                return;
            }
            int columns = volume.sizeX() * volume.sizeZ();
            if (first > 0 && volume.blockY(first) != minInclusive) {
                input.sampleVolume(context, output, volume);
                for (int column = 0; column < columns; column++) {
                    fillOutside(output, column * sizeY, first, end, sizeY);
                }
                return;
            }
            int rows = end - first;
            DensityVolume band = new DensityVolume(
                    volume.sizeX(),
                    rows,
                    volume.sizeZ(),
                    volume.minBlockX(),
                    volume.blockY(first),
                    volume.minBlockZ(),
                    volume.stepBlockX(),
                    stepY,
                    volume.stepBlockZ());
            try (ScopedDensityBuffer inside = context.acquireBuffer(band)) {
                input.sampleVolume(context, inside, band);
                // y is the fastest index of a volume, so a column of x and z is a run of sizeY values.
                for (int column = 0; column < columns; column++) {
                    int to = column * sizeY;
                    int from = column * rows;
                    for (int row = 0; row < rows; row++) {
                        output.set(to + first + row, inside.get(from + row));
                    }
                    fillOutside(output, to, first, end, sizeY);
                }
            }
        }

        private void fillOutside(DensityBuffer output, int column, int first, int end, int sizeY) {
            output.setRange(column, first, outside);
            output.setRange(column + end, sizeY - end, outside);
        }
    }
}
