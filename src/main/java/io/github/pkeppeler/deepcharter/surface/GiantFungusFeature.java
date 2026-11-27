package io.github.pkeppeler.deepcharter.surface;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.surface.GiantLayout.Fit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Grows at most one giant fungus (#1202) in a chunk of one zone of a layer: a great stalk ({@link GreatStalk}), a
 * parasol ({@link ParasolShape}) or a spire cluster ({@link SpireShape}), picked by the zone's {@code weights}, in one
 * chunk in {@code rarity}.
 *
 * <p>Whether a chunk has a site, and which form it grows, come from a hash of the world seed, the chunk, the layer and
 * the zone, never from the world: so a chunk knows what its west and north neighbours pick without reading them, and a
 * chunk whose form matches either re-rolls once, which gives runs of mixed forms instead of rows of one. The shape
 * itself is rolled from the feature's own random. The giant is fitted whole into its chunk (ADR 0088): moved or
 * mirrored, never cut.
 *
 * <p>It runs once per chunk with the zone's rows picked by its placement, and finds its own floor: it tries a few
 * columns of the chunk, and on each the floors of the zone's rows with room over them.
 *
 * @param layer   the layer, counted from 1
 * @param zone    the zone, 0 for the top third of the layer
 * @param rarity  one chunk in this many has a site
 * @param weights how often each form grows at a site
 * @param parasol how this zone's parasols grow
 */
public record GiantFungusFeature(int layer, int zone, int rarity, Weights weights, ParasolShape.Style parasol)
        implements Feature {
    public static final MapCodec<GiantFungusFeature> CODEC =
            RecordCodecBuilder.<GiantFungusFeature>mapCodec(instance -> instance.group(
                            Codec.intRange(1, Integer.MAX_VALUE)
                                    .fieldOf("layer")
                                    .forGetter(GiantFungusFeature::layer),
                            Codec.intRange(0, Zones.COUNT - 1).fieldOf("zone").forGetter(GiantFungusFeature::zone),
                            Codec.intRange(1, Integer.MAX_VALUE)
                                    .fieldOf("rarity")
                                    .forGetter(GiantFungusFeature::rarity),
                            Weights.CODEC.fieldOf("weights").forGetter(GiantFungusFeature::weights),
                            ParasolShape.Style.CODEC
                                    .optionalFieldOf("parasol", ParasolShape.Style.DEFAULT)
                                    .forGetter(GiantFungusFeature::parasol))
                    .apply(instance, GiantFungusFeature::new));

    /**
     * Columns of the chunk a site tries before it gives up, and how many times a shape shrinks on one before the next.
     */
    private static final int COLUMNS = 6;

    private static final int SHRINKS = 3;
    /** The most rows of open air a site counts over a floor. */
    private static final int MOST_ROOM = 40;
    /** Columns a great stalk site tries: it needs a flat 3x3 floor, rarer than a floor. */
    private static final int GREAT_COLUMNS = 16;
    /** The rows of air a great stalk needs over its floor: its tallest trunk and its cap. */
    private static final int GREAT_ROOM = 18;
    /**
     * How far a great stalk's origin, the corner of its 3x3 base, keeps from the chunk's sides: its flare and shelves
     * reach 2 out.
     */
    private static final int GREAT_MARGIN = 2;

    private static final long SITE = 0x5175E5L;
    private static final long FORM = 0xF0E3L;
    private static final long REROLL = 0x2E2011L;

    /** The forms a giant fungus takes. */
    public enum Form implements StringRepresentable {
        GREAT_STALK,
        PARASOL,
        SPIRES;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How often each form grows at a site: weights, not chances, so they need not sum to 1. */
    public record Weights(double greatStalk, double parasol, double spires) {
        public static final Codec<Weights> CODEC = RecordCodecBuilder.<Weights>create(instance -> instance.group(
                                Codec.doubleRange(0, 1).fieldOf("great_stalk").forGetter(Weights::greatStalk),
                                Codec.doubleRange(0, 1).fieldOf("parasol").forGetter(Weights::parasol),
                                Codec.doubleRange(0, 1).fieldOf("spires").forGetter(Weights::spires))
                        .apply(instance, Weights::new))
                .validate(weights -> weights.greatStalk + weights.parasol + weights.spires > 0
                        ? DataResult.success(weights)
                        : DataResult.error(() -> "a giant fungus needs a form with a weight over 0"));

        /** The form a number from 0 up to 1 picks. */
        Form pick(double roll) {
            double at = roll * (greatStalk + parasol + spires);
            if (at < greatStalk) {
                return Form.GREAT_STALK;
            }
            return at < greatStalk + parasol || spires == 0 ? Form.PARASOL : Form.SPIRES;
        }
    }

    @Override
    public MapCodec<GiantFungusFeature> codec() {
        return CODEC;
    }

    /**
     * A hash of the world seed, a chunk, the layer, the zone and a salt, spread over all 64 bits (SplitMix64's
     * finaliser).
     */
    private long hash(long seed, int chunkX, int chunkZ, long salt) {
        long h = seed ^ salt * 0x9E3779B97F4A7C15L;
        h += chunkX * 0xC2B2AE3D27D4EB4FL
                + chunkZ * 0x165667B19E3779F9L
                + zone * 0x27D4EB2F165667C5L
                + layer * 0x9FB21C651E98DF25L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private double unit(long seed, int chunkX, int chunkZ, long salt) {
        return (hash(seed, chunkX, chunkZ, salt) >>> 11) * 0x1.0p-53;
    }

    /** Whether the chunk has a site of this zone, in a world of {@code seed}: one chunk in {@link #rarity}. */
    public boolean site(long seed, int chunkX, int chunkZ) {
        return unit(seed, chunkX, chunkZ, SITE) * rarity < 1;
    }

    /** The form a site picks before it looks at its neighbours. */
    private Form first(long seed, int chunkX, int chunkZ) {
        return weights.pick(unit(seed, chunkX, chunkZ, FORM));
    }

    /**
     * The form the site of a chunk grows, in a world of {@code seed}: its own pick, rolled once more if the site west
     * of it or north of it picked the same. A neighbour's pick is the hash's, so no chunk reads another (ADR 0088).
     */
    public Form form(long seed, int chunkX, int chunkZ) {
        Form form = first(seed, chunkX, chunkZ);
        boolean west = site(seed, chunkX - 1, chunkZ) && first(seed, chunkX - 1, chunkZ) == form;
        boolean north = site(seed, chunkX, chunkZ - 1) && first(seed, chunkX, chunkZ - 1) == form;
        return west || north ? weights.pick(unit(seed, chunkX, chunkZ, REROLL)) : form;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        ChunkPos chunk = ChunkPos.containing(origin);
        long seed = level.getSeed();
        if (!site(seed, chunk.x(), chunk.z())) {
            return false;
        }
        LayerChain.Band band = LayerChain.layerBand(generator, level, layer, "A giant_fungus feature");
        Zones.Span span = Zones.span(band.minY(), band.height(), zone);
        Sites sites = new Sites(level, chunk, span, random);
        // A site with no room for its form grows spires, which suit a low roof and uneven ground best.
        return switch (form(seed, chunk.x(), chunk.z())) {
            case GREAT_STALK -> sites.greatStalk() || sites.spires();
            case PARASOL -> sites.parasol(parasol) || sites.spires();
            case SPIRES -> sites.spires();
        };
    }

    /** A floor of a column: the first open row over solid ground, and the rows of open air over it. */
    private record Floor(BlockPos pos, int room) {}

    /** The search for a place to grow, in one chunk and the rows of one zone. */
    private static final class Sites {
        private final WorldGenLevel level;
        private final ChunkPos chunk;
        private final Zones.Span span;
        private final RandomSource random;
        private final IntUnaryOperator ints;

        Sites(WorldGenLevel level, ChunkPos chunk, Zones.Span span, RandomSource random) {
            this.level = level;
            this.chunk = chunk;
            this.span = span;
            this.random = random;
            this.ints = random::nextInt;
        }

        /**
         * The floors of the column at {@code localX}, {@code localZ} in the zone's rows, with at least {@code least}
         * rows of room.
         */
        private List<Floor> floors(int localX, int localZ, int least) {
            List<Floor> found = new ArrayList<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int x = chunk.getMinBlockX() + localX;
            int z = chunk.getMinBlockZ() + localZ;
            int room = 0;
            // From above the zone, so a floor near its top counts the air over it.
            for (int y = span.high() + MOST_ROOM; y >= span.low(); y--) {
                pos.set(x, y, z);
                if (GiantFungus.open(level.getBlockState(pos))) {
                    room = Math.min(room + 1, MOST_ROOM);
                    continue;
                }
                BlockPos floor = pos.above().immutable();
                if (room >= least
                        && floor.getY() <= span.high()
                        && floor.getY() >= span.low()
                        && level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)) {
                    found.add(new Floor(floor, room));
                }
                room = 0;
            }
            return found;
        }

        private Optional<Floor> anyFloor(int localX, int localZ, int least) {
            List<Floor> floors = floors(localX, localZ, least);
            return floors.isEmpty() ? Optional.empty() : Optional.of(floors.get(random.nextInt(floors.size())));
        }

        /** The floor of the column with the most room over it: a parasol is as tall as its room lets it be. */
        private Optional<Floor> roomiestFloor(int localX, int localZ, int least) {
            return floors(localX, localZ, least).stream().max((a, b) -> Integer.compare(a.room(), b.room()));
        }

        /**
         * The floor of the column at the base of {@code fit} nearest {@code near}'s row, within 2 rows, with {@code
         * least} rows of room.
         */
        private Optional<Floor> floorNear(Fit fit, Floor near, int least) {
            return floors(fit.baseX(), fit.baseZ(), least).stream()
                    .filter(floor -> Math.abs(floor.pos().getY() - near.pos().getY()) <= 2)
                    .min((a, b) -> Integer.compare(
                            Math.abs(a.pos().getY() - near.pos().getY()),
                            Math.abs(b.pos().getY() - near.pos().getY())));
        }

        private int column() {
            return 2 + random.nextInt(GiantLayout.CHUNK - 4);
        }

        boolean greatStalk() {
            for (int tries = 0; tries < GREAT_COLUMNS; tries++) {
                int x = GREAT_MARGIN + random.nextInt(GiantLayout.CHUNK - 2 * GREAT_MARGIN - 2);
                int z = GREAT_MARGIN + random.nextInt(GiantLayout.CHUNK - 2 * GREAT_MARGIN - 2);
                Optional<Floor> floor = anyFloor(x + 1, z + 1, GREAT_ROOM);
                if (floor.isPresent()
                        && flat(floor.get().pos())
                        && GreatStalk.place(
                                level, random, floor.get().pos().offset(-1, 0, -1), true, GiantFungus::open)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the 3x3 round {@code middle} is open and stands on solid floor in every column: a braid's trunks need
         * it.
         */
        private boolean flat(BlockPos middle) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos cell = middle.offset(dx, 0, dz);
                    if (!GiantFungus.open(level.getBlockState(cell)) || !GiantFungus.standsOnGround(level, cell)) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * Grows {@code layout} fitted into the chunk as near {@code wantX}, {@code wantZ} as it goes, on the floor of
         * its base's column nearest {@code near}'s row. Returns whether it grew.
         */
        private boolean grow(GiantLayout layout, int wantX, int wantZ, Floor near) {
            Optional<Fit> fit = layout.fitInChunk(wantX, wantZ);
            Optional<Floor> base = fit.flatMap(f -> floorNear(f, near, layout.maxY() + 1));
            return base.isPresent()
                    && GiantFungus.place(
                            level, chunk, fit.get(), base.get().pos().getY());
        }

        boolean parasol(ParasolShape.Style style) {
            for (int tries = 0; tries < COLUMNS; tries++) {
                int wantX = column();
                int wantZ = column();
                Optional<Floor> floor = roomiestFloor(wantX, wantZ, ParasolShape.LEAST_STEM + ParasolShape.CAP_ROWS);
                if (floor.isEmpty()) {
                    continue;
                }
                Optional<ParasolShape.Params> params =
                        ParasolShape.roll(ints, floor.get().room() - ParasolShape.CAP_ROWS, style);
                for (int shrink = 0; params.isPresent() && shrink <= SHRINKS; shrink++) {
                    if (grow(ParasolShape.layout(params.get(), ints), wantX, wantZ, floor.get())) {
                        return true;
                    }
                    // A ledge, a column or a lower roof in the cap's way: a shorter stem and a narrower cap, then
                    // another column.
                    params = ParasolShape.smaller(params.get());
                }
            }
            return false;
        }

        boolean spires() {
            for (int tries = 0; tries < COLUMNS; tries++) {
                int wantX = column();
                int wantZ = column();
                Optional<Floor> floor = anyFloor(wantX, wantZ, SpireShape.LEAST_ROOM + 2);
                if (floor.isEmpty()) {
                    continue;
                }
                Optional<SpireShape.Params> params =
                        SpireShape.roll(ints, floor.get().room());
                for (int shrink = 0; params.isPresent() && shrink <= SHRINKS; shrink++) {
                    if (grow(SpireShape.layout(params.get(), ints), wantX, wantZ, floor.get())) {
                        return true;
                    }
                    params = SpireShape.shorter(params.get(), 3);
                }
            }
            return false;
        }
    }
}
