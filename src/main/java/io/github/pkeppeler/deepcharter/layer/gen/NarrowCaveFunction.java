package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Interval;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.ClampFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.UnaryFunction;

/**
 * A narrow cave term (#1472): {@code clamp((interpolated(width) - max(|interpolated(ridge)|, ...)) * sharpness, 0, 1)}, the
 * same value, float for float, as that expression written in vanilla density functions ({@link #expanded}), at less cost.
 * A narrow term is open only where every ridge is near zero, so most interpolation cells are shut whole: where the
 * corners prove {@code width} under every ridge's magnitude throughout a cell, the cell is 0 without interpolating a
 * block. The other cells repeat vanilla's {@code InterpolatedFunction$Sampler.fillCell} arithmetic in the same order.
 *
 * @param width       the width, at most this far from zero a ridge opens; interpolated
 * @param ridges      the ridges, open where all are near zero; each interpolated, then its magnitude taken
 * @param sharpness   how fast the term goes from shut to open
 * @param cellSizeXz  the interpolation cell's width
 * @param cellSizeY   the interpolation cell's height
 */
public record NarrowCaveFunction(
        DensityFunction width, List<DensityFunction> ridges, float sharpness, int cellSizeXz, int cellSizeY)
        implements DensityFunction {
    public static final MapCodec<NarrowCaveFunction> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    DensityFunction.CODEC.fieldOf("width").forGetter(NarrowCaveFunction::width),
                    DensityFunction.CODEC
                            .listOf(1, Integer.MAX_VALUE)
                            .fieldOf("ridges")
                            .forGetter(NarrowCaveFunction::ridges),
                    Codec.floatRange(0, Float.MAX_VALUE).fieldOf("sharpness").forGetter(NarrowCaveFunction::sharpness),
                    ExtraCodecs.POSITIVE_INT.fieldOf("cell_size_xz").forGetter(NarrowCaveFunction::cellSizeXz),
                    ExtraCodecs.POSITIVE_INT.fieldOf("cell_size_y").forGetter(NarrowCaveFunction::cellSizeY))
            .apply(instance, NarrowCaveFunction::new));

    /**
     * Far more than the rounding of a few lerps and a cell's row steps on values near 1, so a cell skipped by the
     * corners is shut at every block.
     */
    private static final float SLACK = 1.0e-3f;

    public NarrowCaveFunction {
        ridges = List.copyOf(ridges);
    }

    public static void register() {
        Registry.register(
                BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "narrow_cave"),
                CODEC);
    }

    /** The vanilla expression this function equals, which it samples for a volume that is not a block grid and for a point. */
    public DensityFunction expanded() {
        DensityFunction widest = magnitude(ridges.getFirst());
        for (DensityFunction ridge : ridges.subList(1, ridges.size())) {
            widest = new BinaryFunction(BinaryFunction.Type.MAX, widest, magnitude(ridge));
        }
        DensityFunction margin = new BinaryFunction(
                BinaryFunction.Type.ADD,
                new InterpolatedFunction(width, cellSizeXz, cellSizeY),
                new BinaryFunction(BinaryFunction.Type.MUL, widest, new ConstantFunction(-1.0f)));
        return new ClampFunction(
                new BinaryFunction(BinaryFunction.Type.MUL, margin, new ConstantFunction(sharpness)), 0.0f, 1.0f);
    }

    private DensityFunction magnitude(DensityFunction ridge) {
        return new UnaryFunction(UnaryFunction.Type.ABS, new InterpolatedFunction(ridge, cellSizeXz, cellSizeY));
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        DensitySampler[] ridgeSamplers = new DensitySampler[ridges.size()];
        for (int i = 0; i < ridgeSamplers.length; i++) {
            ridgeSamplers[i] = ridges.get(i).compileSampler(context);
        }
        return new Sampler(this, expanded().compileSampler(context), width.compileSampler(context), ridgeSamplers);
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        DensityFunction newWidth = rule.rewrite(width);
        List<DensityFunction> newRidges = ridges.stream().map(rule::rewrite).toList();
        boolean same = newWidth == width;
        for (int i = 0; i < ridges.size(); i++) {
            same &= newRidges.get(i) == ridges.get(i);
        }
        return same ? this : new NarrowCaveFunction(newWidth, newRidges, sharpness, cellSizeXz, cellSizeY);
    }

    @Override
    public Interval range() {
        return expanded().range();
    }

    @Override
    public int domainAxes() {
        return expanded().domainAxes();
    }

    @Override
    public MapCodec<NarrowCaveFunction> codec() {
        return CODEC;
    }

    // class-ok: holds compiled samplers and scratch arrays, not plain values; a record would expose the arrays.
    private static final class Sampler implements DensitySampler {
        private final NarrowCaveFunction function;
        private final DensitySampler vanilla;
        private final DensitySampler width;
        private final DensitySampler[] ridges;
        private final float cellSizeXzInv;
        private final float cellSizeYInv;

        Sampler(NarrowCaveFunction function, DensitySampler vanilla, DensitySampler width, DensitySampler[] ridges) {
            this.function = function;
            this.vanilla = vanilla;
            this.width = width;
            this.ridges = ridges;
            // As InterpolatedFunction.compileSampler works them out.
            this.cellSizeXzInv = 1.0f / function.cellSizeXz;
            this.cellSizeYInv = 1.0f / function.cellSizeY;
        }

        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            return vanilla.sampleValue(context, x, y, z);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            if (volume.stepBlockX() != 1 || volume.stepBlockY() != 1 || volume.stepBlockZ() != 1) {
                vanilla.sampleVolume(context, output, volume);
                return;
            }
            int xz = function.cellSizeXz;
            int cy = function.cellSizeY;
            // The cell grid InterpolatedFunction$Sampler.sampleWithBlockStep builds, corner for corner.
            int minCellX = Mth.floorDiv(volume.minBlockX(), xz);
            int minCellY = Mth.floorDiv(volume.minBlockY(), cy);
            int minCellZ = Mth.floorDiv(volume.minBlockZ(), xz);
            int cellCountX = Mth.floorDiv(volume.maxBlockX(), xz) - minCellX + 1;
            int cellCountY = Mth.floorDiv(volume.maxBlockY(), cy) - minCellY + 1;
            int cellCountZ = Mth.floorDiv(volume.maxBlockZ(), xz) - minCellZ + 1;
            DensityVolume cells = new DensityVolume(
                    Math.floorMod(volume.maxBlockX(), xz) == 0 ? cellCountX : cellCountX + 1,
                    Math.floorMod(volume.maxBlockY(), cy) == 0 ? cellCountY : cellCountY + 1,
                    Math.floorMod(volume.maxBlockZ(), xz) == 0 ? cellCountZ : cellCountZ + 1,
                    minCellX * xz,
                    minCellY * cy,
                    minCellZ * xz,
                    xz,
                    cy,
                    xz);
            ScopedDensityBuffer[] corners = new ScopedDensityBuffer[1 + ridges.length];
            try {
                corners[0] = context.acquireBuffer(cells);
                width.sampleVolume(context, corners[0], cells);
                for (int i = 0; i < ridges.length; i++) {
                    corners[i + 1] = context.acquireBuffer(cells);
                    ridges[i].sampleVolume(context, corners[i + 1], cells);
                }
                Cell cell = new Cell(corners.length);
                for (int cellZ = 0; cellZ < cellCountZ; cellZ++) {
                    int nextZ = Math.min(cellZ + 1, cells.sizeZ() - 1);
                    for (int cellX = 0; cellX < cellCountX; cellX++) {
                        int nextX = Math.min(cellX + 1, cells.sizeX() - 1);
                        for (int cellY = 0; cellY < cellCountY; cellY++) {
                            int nextY = Math.min(cellY + 1, cells.sizeY() - 1);
                            cell.read(corners, cells, cellX, cellY, cellZ, nextX, nextY, nextZ);
                            fill(output, volume, cells, cellX, cellY, cellZ, cell);
                        }
                    }
                }
            } finally {
                // Last acquired first, as nested try-with-resources would.
                for (int i = corners.length - 1; i >= 0; i--) {
                    if (corners[i] != null) {
                        corners[i].close();
                    }
                }
            }
        }

        /** The cell's blocks of the output, as {@code fillCell} bounds them; 0 throughout when the corners shut it. */
        private void fill(
                DensityBuffer output,
                DensityVolume volume,
                DensityVolume cells,
                int cellX,
                int cellY,
                int cellZ,
                Cell cell) {
            int outX = cells.blockX(cellX) - volume.minBlockX();
            int outY = cells.blockY(cellY) - volume.minBlockY();
            int outZ = cells.blockZ(cellZ) - volume.minBlockZ();
            int x0 = Math.max(0, -outX);
            int y0 = Math.max(0, -outY);
            int z0 = Math.max(0, -outZ);
            int x1 = Math.min(function.cellSizeXz, volume.sizeX() - outX) - 1;
            int y1 = Math.min(function.cellSizeY, volume.sizeY() - outY) - 1;
            int z1 = Math.min(function.cellSizeXz, volume.sizeZ() - outZ) - 1;
            boolean shut = cell.shut();
            int inputs = cell.count;
            float[] v00 = cell.v00;
            float[] v01 = cell.v01;
            float[] v10 = cell.v10;
            float[] v11 = cell.v11;
            float[] value = cell.value;
            float[] step = cell.step;
            for (int z = z0; z <= z1; z++) {
                float alphaZ = z * cellSizeXzInv;
                if (!shut) {
                    for (int i = 0; i < inputs; i++) {
                        v00[i] = Mth.lerp(alphaZ, cell.c000[i], cell.c001[i]);
                        v01[i] = Mth.lerp(alphaZ, cell.c010[i], cell.c011[i]);
                        v10[i] = Mth.lerp(alphaZ, cell.c100[i], cell.c101[i]);
                        v11[i] = Mth.lerp(alphaZ, cell.c110[i], cell.c111[i]);
                    }
                }
                for (int x = x0; x <= x1; x++) {
                    int index = volume.indexUnchecked(outX + x, outY + y0, outZ + z);
                    if (shut) {
                        for (int y = y0; y <= y1; y++) {
                            output.set(index++, 0.0f);
                        }
                        continue;
                    }
                    float alphaX = x * cellSizeXzInv;
                    for (int i = 0; i < inputs; i++) {
                        float v0 = Mth.lerp(alphaX, v00[i], v10[i]);
                        float v1 = Mth.lerp(alphaX, v01[i], v11[i]);
                        step[i] = (v1 - v0) * cellSizeYInv;
                        value[i] = v0 + step[i] * y0;
                    }
                    for (int y = y0; y <= y1; y++) {
                        output.set(index++, open(value, inputs));
                        for (int i = 0; i < inputs; i++) {
                            value[i] += step[i];
                        }
                    }
                }
            }
        }

        /** The expression at one block from its interpolated inputs, in the order vanilla's samplers apply it. */
        private float open(float[] value, int inputs) {
            float widest = Math.abs(value[1]);
            for (int i = 2; i < inputs; i++) {
                float next = Math.abs(value[i]);
                if (next > widest) {
                    widest = next;
                }
            }
            float margin = value[0] + widest * -1.0f;
            return Mth.clamp(margin * function.sharpness, 0.0f, 1.0f);
        }
    }

    /** One cell's corners for every input (width first), and the scratch of its interpolation. */
    // class-ok: mutable scratch reused across cells.
    private static final class Cell {
        final int count;
        final float[] c000;
        final float[] c100;
        final float[] c010;
        final float[] c110;
        final float[] c001;
        final float[] c101;
        final float[] c011;
        final float[] c111;
        final float[] v00;
        final float[] v01;
        final float[] v10;
        final float[] v11;
        final float[] value;
        final float[] step;

        Cell(int count) {
            this.count = count;
            c000 = new float[count];
            c100 = new float[count];
            c010 = new float[count];
            c110 = new float[count];
            c001 = new float[count];
            c101 = new float[count];
            c011 = new float[count];
            c111 = new float[count];
            v00 = new float[count];
            v01 = new float[count];
            v10 = new float[count];
            v11 = new float[count];
            value = new float[count];
            step = new float[count];
        }

        void read(
                DensityBuffer[] corners,
                DensityVolume cells,
                int cellX,
                int cellY,
                int cellZ,
                int nextX,
                int nextY,
                int nextZ) {
            for (int i = 0; i < count; i++) {
                DensityBuffer buffer = corners[i];
                c000[i] = buffer.get(cells.indexUnchecked(cellX, cellY, cellZ));
                c100[i] = buffer.get(cells.indexUnchecked(nextX, cellY, cellZ));
                c001[i] = buffer.get(cells.indexUnchecked(cellX, cellY, nextZ));
                c101[i] = buffer.get(cells.indexUnchecked(nextX, cellY, nextZ));
                c010[i] = buffer.get(cells.indexUnchecked(cellX, nextY, cellZ));
                c110[i] = buffer.get(cells.indexUnchecked(nextX, nextY, cellZ));
                c011[i] = buffer.get(cells.indexUnchecked(cellX, nextY, nextZ));
                c111[i] = buffer.get(cells.indexUnchecked(nextX, nextY, nextZ));
            }
        }

        /**
         * Whether the term is 0 at every block of the cell: an interpolated value lies between its corners (to within
         * {@link #SLACK}), so the width stays under its highest corner and a ridge whose corners share a sign stays
         * above its smallest magnitude; when the width's top is under some ridge's floor, the margin is negative
         * everywhere and the clamp gives 0.
         */
        boolean shut() {
            float widthTop = max(0);
            float ridgeFloor = 0.0f;
            for (int i = 1; i < count; i++) {
                float low = min(i);
                float high = max(i);
                float floor = low > 0.0f ? low : high < 0.0f ? -high : 0.0f;
                ridgeFloor = Math.max(ridgeFloor, floor);
            }
            return widthTop + SLACK < ridgeFloor - SLACK;
        }

        private float max(int i) {
            return Math.max(
                    Math.max(Math.max(c000[i], c100[i]), Math.max(c010[i], c110[i])),
                    Math.max(Math.max(c001[i], c101[i]), Math.max(c011[i], c111[i])));
        }

        private float min(int i) {
            return Math.min(
                    Math.min(Math.min(c000[i], c100[i]), Math.min(c010[i], c110[i])),
                    Math.min(Math.min(c001[i], c101[i]), Math.min(c011[i], c111[i])));
        }
    }
}
