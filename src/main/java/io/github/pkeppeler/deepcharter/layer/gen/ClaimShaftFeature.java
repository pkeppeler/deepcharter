package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * A first-generation miner's shaft in layer 1 (#1277, LORE section 11): a hand-dug 3 x 3 hollow, {@code minDepth} to
 * {@code maxDepth} rows deep, timbered with stalk planks every few rows (some rotted away), a ladder down one wall with
 * rungs gone, candle niches in the walls with burnt-down stubs and, more often the deeper the shaft, a prayer card, and
 * at the foot a short drift with a lunch pail left in it. Its top is a plug of rubble where the collar fell in, or,
 * where the rock's top or a cave is above it, an open mouth. It cuts through whatever caves it meets, and the zone
 * fill's rock and ore round it stay as they were.
 *
 * <p>It writes only inside its own chunk (ADR 0088): {@link #plan} keeps the shaft, its drift and the shell it seals
 * inside the chunk of the origin, and the feature reads only that chunk. Before the carve it seals the shell, as every
 * hollow cut in a layer does (the M2 rules): lava or gas the zone fill left there becomes the rock of the zone.
 *
 * @param layer    the layer, counted from 1
 * @param minDepth the fewest rows of hollow, from 8
 * @param maxDepth the most
 */
public record ClaimShaftFeature(int layer, int minDepth, int maxDepth) implements Feature {
    /** A shaft has room for a niche, a set of timber and the drift. */
    private static final int MIN_DEPTH = 8;

    private static final int MAX_DEPTH = 64;

    public static final MapCodec<ClaimShaftFeature> CODEC = RecordCodecBuilder.<ClaimShaftFeature>mapCodec(instance ->
                    instance.group(
                                    Codec.intRange(1, Integer.MAX_VALUE)
                                            .fieldOf("layer")
                                            .forGetter(ClaimShaftFeature::layer),
                                    Codec.intRange(MIN_DEPTH, MAX_DEPTH)
                                            .fieldOf("min_depth")
                                            .forGetter(ClaimShaftFeature::minDepth),
                                    Codec.intRange(MIN_DEPTH, MAX_DEPTH)
                                            .fieldOf("max_depth")
                                            .forGetter(ClaimShaftFeature::maxDepth))
                            .apply(instance, ClaimShaftFeature::new))
            .validate(shaft -> shaft.minDepth <= shaft.maxDepth
                    ? DataResult.success(shaft)
                    : DataResult.error(() -> "min_depth " + shaft.minDepth + " is over max_depth " + shaft.maxDepth));

    /**
     * Blocks from the axis to the hollow's wall (1), to the timber and the niches (2) and to the shell that is sealed
     * (3).
     */
    private static final int HOLLOW = 1;

    private static final int WALL = 2;
    private static final int REACH = 3;

    /** Blocks the drift runs out past the hollow's wall, and rows it is tall. */
    private static final int DRIFT = 3;

    private static final int DRIFT_HEIGHT = 2;

    /** Rows between sets of timber, and between niches. */
    private static final int SET_SPACING = 5;

    private static final int NICHE_SPACING = 7;

    /** Rows a shaft with no rock at its top looks down for the rock's top, where its mouth opens. */
    private static final int MOUTH_SEARCH = 24;

    /** Rows of rubble in the plug of a shaft whose top is in rock. */
    private static final int PLUG = 2;

    /**
     * The chance a block of timber has rotted away, a rung has gone, and a niche holds a prayer card by zone (top
     * first).
     */
    private static final double ROTTED = 0.2;

    private static final double RUNG_GONE = 0.12;
    private static final double[] PRAYER_CARD = {0.2, 0.35, 0.55};

    /** The colony's fungal-wood planks, which name no constant: resolved once, and loud if the id is gone. */
    private static @Nullable BlockState timber;

    /** The chance the drift also holds a salvage crate. */
    private static final double CRATE = 0.3;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public static void register() {
        Registry.register(
                BuiltInRegistries.FEATURE_TYPE,
                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "claim_shaft"),
                CODEC);
    }

    @Override
    public MapCodec<ClaimShaftFeature> codec() {
        return CODEC;
    }

    /**
     * Where a shaft goes: its axis column, its top row (the origin's, before {@link #place} looks for the rock), its
     * rows of hollow and the way its drift runs from the foot. The axis is {@link #REACH} blocks clear of the chunk's
     * sides, and the drift runs towards the chunk's middle, so {@link #bounds} lies in the chunk of the origin.
     */
    public record Plan(BlockPos top, int depth, Direction drift) {
        /** The lowest row of hollow. */
        int bottom() {
            return top.getY() - depth + 1;
        }

        /**
         * Every block the shaft may read or write: its hollow, timber, niches, drift, plug and floor, and the shell
         * round them.
         */
        public BoundingBox bounds() {
            int x = top.getX();
            int z = top.getZ();
            int outward = HOLLOW + DRIFT + 1;
            return new BoundingBox(
                    x - (drift == Direction.WEST ? outward : REACH),
                    bottom() - 2,
                    z - (drift == Direction.NORTH ? outward : REACH),
                    x + (drift == Direction.EAST ? outward : REACH),
                    top.getY() + 1,
                    z + (drift == Direction.SOUTH ? outward : REACH));
        }
    }

    /** The plan of the shaft whose placement picked {@code origin}; it draws from {@code random} only. */
    public Plan plan(RandomSource random, BlockPos origin) {
        ChunkPos chunk = ChunkPos.containing(origin);
        int span = 16 - 2 * REACH;
        int x = chunk.getMinBlockX() + REACH + random.nextInt(span);
        int z = chunk.getMinBlockZ() + REACH + random.nextInt(span);
        int depth = random.nextIntBetweenInclusive(minDepth, maxDepth);
        // The drift runs towards the chunk's middle along one axis, so it has room in the chunk.
        Direction drift = random.nextBoolean()
                ? (x - chunk.getMinBlockX() < 8 ? Direction.EAST : Direction.WEST)
                : (z - chunk.getMinBlockZ() < 8 ? Direction.SOUTH : Direction.NORTH);
        return new Plan(new BlockPos(x, origin.getY(), z), depth, drift);
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        Band band = LayerChain.layerBand(generator, level, layer, "A claim shaft");
        ChunkAccess chunk = level.getChunk(origin);
        Plan plan = plan(random, origin);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // A top in open space (the room over the rock, a cave) moves down to the rock, where the shaft's mouth opens.
        int top = plan.top().getY();
        boolean mouth = false;
        int x = plan.top().getX();
        int z = plan.top().getZ();
        for (int drop = 0; drop <= MOUTH_SEARCH && !isRock(chunk.getBlockState(pos.set(x, top, z))); drop++) {
            top--;
            mouth = true;
        }
        if (!isRock(chunk.getBlockState(pos.set(x, top, z)))) {
            return false;
        }
        Plan placed = new Plan(new BlockPos(x, top, z), plan.depth(), plan.drift());
        BoundingBox bounds = placed.bounds();
        int floor = Math.max(band.minY(), chunk.getMinY()) + LayerTuning.DEFAULT.crustThickness() + 2;
        if (bounds.minY() < floor || bounds.maxY() > Math.min(band.maxY(), chunk.getMinY() + chunk.getHeight() - 1)) {
            return false;
        }
        Shaft shaft = new Shaft(chunk, band, placed, random);
        shaft.seal(bounds);
        shaft.carve(mouth);
        shaft.dress(mouth);
        return true;
    }

    private static BlockState timber() {
        BlockState planks = timber;
        if (planks == null) {
            Identifier id = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "stalk_planks");
            planks = BuiltInRegistries.BLOCK
                    .getOptional(id)
                    .orElseThrow(() -> new IllegalStateException("no block " + id + " to timber a claim shaft"))
                    .defaultBlockState();
            timber = planks;
        }
        return planks;
    }

    /**
     * Natural rock (the layer's, an ore, a gas pocket, rubble): what a shaft is dug through. Air, lava, growth and
     * placed blocks are not.
     */
    private static boolean isRock(BlockState state) {
        return state.is(HazardBlocks.NATURAL_ROCK);
    }

    /** One shaft as it is written into its chunk. */
    private static final class Shaft {
        private final ChunkAccess chunk;
        private final Band band;
        private final Plan plan;
        private final RandomSource random;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final Direction ladderWall;
        /** The columns carved at the foot, hollow and drift: the floor that takes what fell. */
        private final List<BlockPos> foot = new ArrayList<>();

        Shaft(ChunkAccess chunk, Band band, Plan plan, RandomSource random) {
            this.chunk = chunk;
            this.band = band;
            this.plan = plan;
            this.random = random;
            this.ladderWall = plan.drift().getOpposite();
        }

        private BlockState get(int x, int y, int z) {
            return chunk.getBlockState(pos.set(x, y, z));
        }

        private void set(int x, int y, int z, BlockState state) {
            chunk.setBlockState(pos.set(x, y, z), state, Block.UPDATE_CLIENTS);
        }

        /**
         * Seals the shell: lava and gas in {@code bounds} become the layer rock found in the same row of the bounds, so a
         * seal near a strata contact is the rock of the wall round it; a row with none takes the layer's own rock.
         */
        void seal(BoundingBox bounds) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                BlockState rock = LayerBlocks.layerGround(band.layer()).defaultBlockState();
                findRock:
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        BlockState state = get(x, y, z);
                        if (state.is(LayerBlocks.GROUND)) {
                            rock = state;
                            break findRock;
                        }
                    }
                }
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                        if (RoomSeal.needsSealing(get(x, y, z))) {
                            set(x, y, z, rock);
                        }
                    }
                }
            }
        }

        void carve(boolean mouth) {
            int x = plan.top().getX();
            int z = plan.top().getZ();
            int top = plan.top().getY();
            int bottom = plan.bottom();
            int plug = mouth ? 0 : PLUG;
            for (int y = bottom; y <= top; y++) {
                for (int u = -HOLLOW; u <= HOLLOW; u++) {
                    for (int v = -HOLLOW; v <= HOLLOW; v++) {
                        // The collar fell in: a plug of rubble with a gap or two in it.
                        boolean plugged = y > top - plug && random.nextFloat() > 0.15F;
                        set(x + u, y, z + v, plugged ? LayerBlocks.PACKED_SHALE_RUBBLE.defaultBlockState() : AIR);
                    }
                }
            }
            for (int u = -HOLLOW; u <= HOLLOW; u++) {
                for (int v = -HOLLOW; v <= HOLLOW; v++) {
                    foot.add(new BlockPos(x + u, bottom, z + v));
                }
            }
            Direction drift = plan.drift();
            Direction side = drift.getClockWise();
            for (int out = HOLLOW + 1; out <= HOLLOW + DRIFT; out++) {
                for (int across = -HOLLOW; across <= HOLLOW; across++) {
                    // The drift narrows as it runs out: its last block is one wide.
                    if (out == HOLLOW + DRIFT && across != 0) {
                        continue;
                    }
                    int dx = x + drift.getStepX() * out + side.getStepX() * across;
                    int dz = z + drift.getStepZ() * out + side.getStepZ() * across;
                    for (int y = bottom; y < bottom + DRIFT_HEIGHT; y++) {
                        set(dx, y, dz, AIR);
                    }
                    foot.add(new BlockPos(dx, bottom, dz));
                }
            }
            // The floor: what fell, over the rock, under the carved foot only.
            for (BlockPos at : foot) {
                if (isRock(get(at.getX(), bottom - 1, at.getZ())) && random.nextFloat() < 0.6F) {
                    set(at.getX(), bottom - 1, at.getZ(), LayerBlocks.PACKED_SHALE_RUBBLE.defaultBlockState());
                }
            }
        }

        void dress(boolean mouth) {
            int x = plan.top().getX();
            int z = plan.top().getZ();
            int top = plan.top().getY() - (mouth ? 0 : PLUG);
            int bottom = plan.bottom();
            BlockState timber = timber();
            // Sets of timber in the walls, the top one at a mouth's rim.
            for (int y = mouth ? top : top - 1; y > bottom + 1; y -= SET_SPACING) {
                for (int u = -WALL; u <= WALL; u++) {
                    for (int v = -WALL; v <= WALL; v++) {
                        if (Math.max(Math.abs(u), Math.abs(v)) == WALL
                                && isRock(get(x + u, y, z + v))
                                && random.nextDouble() >= ROTTED) {
                            set(x + u, y, z + v, timber);
                        }
                    }
                }
            }
            // The ladder, on the wall across from the drift, where a wall still holds it.
            int lx = x + ladderWall.getStepX() * HOLLOW;
            int lz = z + ladderWall.getStepZ() * HOLLOW;
            BlockState rung = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, ladderWall.getOpposite());
            for (int y = bottom; y <= top; y++) {
                boolean held = !get(lx + ladderWall.getStepX(), y, lz + ladderWall.getStepZ())
                        .isAir();
                if (held && get(lx, y, lz).isAir() && random.nextDouble() >= RUNG_GONE) {
                    set(lx, y, lz, rung);
                }
            }
            // Candle niches, every few rows, on the two walls beside the ladder's.
            Direction[] walls = {ladderWall.getClockWise(), ladderWall.getCounterClockWise()};
            int niche = 0;
            for (int y = top - 3; y >= bottom + 3; y -= NICHE_SPACING) {
                Direction wall = walls[niche++ % 2];
                niche(x, y, z, wall);
            }
            // The foot: the pail at the drift's end, a stub where the drift meets the hollow, and now and then a crate.
            Direction drift = plan.drift();
            int far = HOLLOW + DRIFT;
            set(x + drift.getStepX() * far, bottom, z + drift.getStepZ() * far, facing(LayerBlocks.LUNCH_PAIL, drift));
            Direction side = drift.getClockWise();
            int corner = HOLLOW + 1;
            set(
                    x + drift.getStepX() * corner + side.getStepX(),
                    bottom,
                    z + drift.getStepZ() * corner + side.getStepZ(),
                    stub());
            if (random.nextDouble() < CRATE) {
                set(
                        x + drift.getStepX() * corner - side.getStepX(),
                        bottom,
                        z + drift.getStepZ() * corner - side.getStepZ(),
                        facing(SurfaceBlocks.SALVAGE_CRATE, drift));
            }
        }

        /**
         * A niche two blocks wide and two tall in {@code wall}, at row {@code y}: a candle stub, and a prayer card or a
         * second stub.
         */
        private void niche(int x, int y, int z, Direction wall) {
            Direction along = wall.getClockWise();
            int nx = x + wall.getStepX() * WALL;
            int nz = z + wall.getStepZ() * WALL;
            // Rock for the niche's two rows and under them, so a stub never stands over a cave.
            for (int a = 0; a <= 1; a++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (!isRock(get(nx + along.getStepX() * a, y + dy, nz + along.getStepZ() * a))) {
                        return;
                    }
                }
            }
            for (int a = 0; a <= 1; a++) {
                set(nx + along.getStepX() * a, y + 1, nz + along.getStepZ() * a, AIR);
            }
            set(nx, y, nz, stub());
            double card = PRAYER_CARD[Zones.index(band.minY(), band.height(), y)];
            set(
                    nx + along.getStepX(),
                    y,
                    nz + along.getStepZ(),
                    random.nextDouble() < card ? facing(LayerBlocks.PRAYER_CARD, wall.getOpposite()) : stub());
        }

        /** A candle burnt down and out: one to three stubs, unlit. */
        private BlockState stub() {
            return Blocks.CANDLE
                    .defaultBlockState()
                    .setValue(CandleBlock.CANDLES, random.nextIntBetweenInclusive(1, 3))
                    .setValue(CandleBlock.LIT, false);
        }

        private static BlockState facing(Block block, Direction direction) {
            return block.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, direction);
        }
    }
}
