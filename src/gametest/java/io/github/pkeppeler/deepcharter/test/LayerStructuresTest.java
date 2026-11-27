package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.handbook.HandbookRegistry;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.sound.ambience.AmbienceRegistry;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #79: layer structures stand in their zones, one site of each kind in every 384-block square, from
 * the world seed alone, and the colony holds Notes N01 to N04. The generation test draws real chunks of both layers.
 */
public class LayerStructuresTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(LayerStructuresTest.class);
    private static final int SPACING = 384;
    /** A spacing cell that is far from the colony's Conduit, so no structure shares its blocks. */
    static final int CELL = 3;

    private static final int PAD_REACH = 40;
    private static final int PAD_HEIGHT = 8;
    /** How near a colony Note is to the anchor of its building, in blocks on the map. */
    private static final double NEAR_ANCHOR = 6;

    private static final int PROSPECTOR_CELLS = 6;
    /** Far from the layer-2 tests, so nothing makes this chunk full first. */
    private static final int SEAL_CELL = 30;
    /** The same for layer 1's seal, in a cell of its own. */
    private static final int LAYER_1_SEAL_CELL = 35;
    /** Rows either side of a strata contact where the wall may be either rock (the contour noise and the bed). */
    private static final int CONTACT_REACH = 12;

    /** Cells of their own for the punch clock's horn: a roof over it at three distances, and none in reach. */
    private static final int HORN_CEILING_CELL = 31;

    private static final int HORN_OPEN_CELL = 32;
    private static final int HORN_ADJACENT_CELL = 33;
    private static final int HORN_ONE_CHAIN_CELL = 34;

    /** A height of open air over the surface's flat ground, with room for a post below it. */
    private static final int OPEN_AIR_Y = TestLayers.worldY(TestLayers.SURFACE, 200);

    private static final int DISK_CELL = 6;
    private static final int CASING_CELL = 7;
    private static final int SEAM_CELL = 8;
    /** A cell of its own for the Kells' shaft, which no other test draws. */
    private static final int KELLS_CELL = 9;
    /** The rail line is 65 blocks long and 5 wide (ADR 0025). */
    private static final int RAILS_HALF_U = 32;

    private static final int RAILS_HALF_V = 2;
    /** A Conduit in no cell a test draws. */
    private static final BlockPos FAR_CONDUIT = TestLayers.pos(TestLayers.SURFACE, -100_000, 64, -100_000);

    /** What a generated site must hold at least: each block is checked for presence, not for a count the blueprint happens to give. */
    private static Map<Block, Integer> minimum(StructureKind kind, int height) {
        return switch (kind) {
            case TOPSOIL_SHAFT, BENCHES_SHAFT, DEEP_SHAFT ->
                Map.of(Blocks.LADDER, height, Blocks.CANDLE, 1, HandbookRegistry.NOTE, 1);
            case GALLERY -> Map.of(Blocks.CONCRETE.black(), 1, Blocks.RAW_IRON_BLOCK, 1, HandbookRegistry.NOTE, 1);
            case PUNCH_CLOCK ->
                Map.of(
                        Blocks.BOOKSHELF,
                        1,
                        Blocks.SEA_LANTERN,
                        1,
                        Blocks.IRON_BLOCK,
                        1,
                        HandbookRegistry.NOTE,
                        1,
                        AmbienceRegistry.COMPANY_HORN,
                        1,
                        AmbienceRegistry.COMPANY_CLOCK,
                        1);
            case RAILS -> Map.of(Blocks.OAK_PLANKS, 1, Blocks.OAK_LOG, 1, Blocks.RAIL, 1);
            case KELLS_SHAFT -> Map.of(SlagBrick.BLOCK, 12, Blocks.OAK_PLANKS, 1, Blocks.RAIL, 1, Blocks.LAVA, 2);
            case WRECK -> Map.of(Blocks.BLACKSTONE, 1, Blocks.DEEPSLATE_TILES, 1, Blocks.RAIL, 1);
        };
    }

    /** The Note each kind holds; the numbers are permanent (ADR 0020). The rail line and the wreck site hold none. */
    private static final Map<StructureKind, Integer> NOTES = Map.of(
            StructureKind.TOPSOIL_SHAFT, 5,
            StructureKind.BENCHES_SHAFT, 6,
            StructureKind.DEEP_SHAFT, 7,
            StructureKind.GALLERY, 8,
            StructureKind.PUNCH_CLOCK, 9);

    @GameTest
    public void zoneSpansPartitionTheLayer(GameTestHelper helper) {
        for (int[] layer : new int[][] {{0, 256}, {-64, 384}, {0, 100}}) {
            int minY = layer[0];
            int height = layer[1];
            int covered = 0;
            for (int index = 0; index < Zones.COUNT; index++) {
                Zones.Span span = Zones.span(minY, height, index);
                covered += span.size();
                for (int y = span.low(); y <= span.high(); y++) {
                    if (Zones.index(minY, height, y) != index) {
                        throw failure(
                                helper,
                                "y=%d is in the span of zone %d but Zones.index says %d (minY %d, height %d)",
                                y,
                                index,
                                Zones.index(minY, height, y),
                                minY,
                                height);
                    }
                }
            }
            if (covered != height) {
                throw failure(helper, "the three zone spans cover %d blocks of %d (minY %d)", covered, height, minY);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void everyKindHasOneSiteInEachCellInsideItsCellAndZone(GameTestHelper helper) {
        BlockPos conduit = conduit(helper);
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            for (StructureKind kind : StructureKind.inLayer(layer)) {
                for (int cellX = -2; cellX <= 1; cellX++) {
                    for (int cellZ = -2; cellZ <= 1; cellZ++) {
                        StructureSite site = StructureSite.in(
                                level.getSeed(),
                                kind,
                                TestLayers.band(level, layer).minY(),
                                TestLayers.band(level, layer).height(),
                                cellX,
                                cellZ,
                                conduit);
                        BoundingBox box = site.bounds();
                        if (box.minX() < cellX * SPACING
                                || box.maxX() >= (cellX + 1) * SPACING
                                || box.minZ() < cellZ * SPACING
                                || box.maxZ() >= (cellZ + 1) * SPACING) {
                            throw failure(helper, "%s in cell (%d, %d) reaches out of it: %s", kind, cellX, cellZ, box);
                        }
                        for (int y = box.minY(); y <= box.maxY(); y++) {
                            if (Zones.index(
                                            TestLayers.band(level, layer).minY(),
                                            TestLayers.band(level, layer).height(),
                                            y)
                                    != kind.zone()) {
                                throw failure(
                                        helper,
                                        "%s in cell (%d, %d) at y=%d is in zone %d, not %d",
                                        kind,
                                        cellX,
                                        cellZ,
                                        y,
                                        Zones.index(
                                                TestLayers.band(level, layer).minY(),
                                                TestLayers.band(level, layer).height(),
                                                y),
                                        kind.zone());
                            }
                        }
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void sitesFollowTheSeedAndNothingElse(GameTestHelper helper) {
        BlockPos conduit = conduit(helper);
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band levelBand = TestLayers.band(level, 2);
        int differing = 0;
        for (StructureKind kind : StructureKind.inLayer(2)) {
            for (int cell = 0; cell < 8; cell++) {
                StructureSite one =
                        StructureSite.in(11L, kind, levelBand.minY(), levelBand.height(), cell, -cell, conduit);
                if (!one.equals(
                        StructureSite.in(11L, kind, levelBand.minY(), levelBand.height(), cell, -cell, conduit))) {
                    throw failure(
                            helper, "%s in cell %d is not the same when asked twice for the same seed", kind, cell);
                }
                if (!one.equals(
                        StructureSite.in(12L, kind, levelBand.minY(), levelBand.height(), cell, -cell, conduit))) {
                    differing++;
                }
            }
        }
        // Of 32 sites, a second seed that moved none of them would be a seed that is not used.
        if (differing < 30) {
            throw failure(helper, "only %d of 32 sites moved with the seed", differing);
        }
        helper.succeed();
    }

    /**
     * The chunks of every site are forced on the first tick and load on the worker threads; every tick polls, under a
     * wall-clock {@link FarChunks.Deadline}, until they are loaded and every condition on every site holds, because a
     * tick budget is a different wall time under load. A wait that was needed is logged. See #365; the failure's census
     * says what stands in the site's place.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void eachStructureGeneratesInItsZone(GameTestHelper helper) {
        BlockPos conduit = conduit(helper);
        List<StructureSite> sites = new ArrayList<>();
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            for (StructureKind kind : StructureKind.inLayer(layer)) {
                StructureSite site = StructureSite.in(
                        level.getSeed(),
                        kind,
                        TestLayers.band(level, layer).minY(),
                        TestLayers.band(level, layer).height(),
                        CELL,
                        CELL,
                        conduit);
                sites.add(site);
                // Loaded on the worker threads; a synchronous getChunk here would hold the server thread (#1016).
                for (ChunkPos chunk : chunksOf(site)) {
                    FarChunks.force(helper, level, chunk.x(), chunk.z());
                }
            }
        }
        FarChunks.Deadline deadline = FarChunks.deadline();
        long startNanos = System.nanoTime();
        Map<StructureKind, String> firstUnmet = new EnumMap<>(StructureKind.class);
        TestTicks.each(helper, () -> {
            List<String> failing = new ArrayList<>();
            for (StructureSite site : sites) {
                ServerLevel level = TestLayers.level(helper, site.kind().layer());
                // The chunk states are read first and without loading: reading a block loads an unloaded chunk.
                String chunks = chunkStates(level, site);
                if (!loaded(level, site)) {
                    failing.add(String.format("%s: chunks not loaded yet: %s", site.kind(), chunks));
                    continue;
                }
                Map<Block, Integer> found = census(level, site);
                List<String> unmet = unmet(level, site, found);
                if (!unmet.isEmpty()) {
                    String now = String.format(
                            "unmet %s; chunks before the read: %s; it holds: %s", unmet, chunks, describe(found));
                    firstUnmet.putIfAbsent(site.kind(), String.format("tick %d: %s", helper.getTick(), now));
                    failing.add(String.format(
                            "%s at %s: first poll, %s. Last poll, tick %d: %s",
                            site.kind(),
                            site.origin().toShortString(),
                            firstUnmet.get(site.kind()),
                            helper.getTick(),
                            now));
                }
            }
            if (!failing.isEmpty()) {
                deadline.await(
                        helper,
                        TestLayers.level(helper, 1),
                        false,
                        () -> String.format("structures did not settle in %d s: %s", FarChunks.WAIT_SECONDS, failing));
                return;
            }
            if (!firstUnmet.isEmpty()) {
                LOGGER.warn(
                        "[structures] layer structures were not all in place on the first poll; they were on tick {} after {} ms (#365). First polls: {}",
                        helper.getTick(),
                        (System.nanoTime() - startNanos) / 1_000_000,
                        firstUnmet);
            }
            helper.succeed();
        });
    }

    /** The chunk of the site's origin, or a retry of the poll that waits for {@link FarChunks#force} to load it. */
    private static LevelChunk loadedChunk(GameTestHelper helper, ServerLevel level, StructureSite site) {
        LevelChunk chunk = level.getChunkSource()
                .getChunkNow(site.origin().getX() >> 4, site.origin().getZ() >> 4);
        if (chunk == null) {
            throw failure(
                    helper,
                    "the chunk of the site at %s is not loaded yet",
                    site.origin().toShortString());
        }
        return chunk;
    }

    /** Whether every chunk that the site's bounds touch is loaded, so that reading a block of it loads nothing. */
    static boolean loaded(ServerLevel level, StructureSite site) {
        return chunksOf(site).stream()
                .allMatch(chunk -> level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null);
    }

    /** Every chunk that the site's bounds touch. */
    static List<ChunkPos> chunksOf(StructureSite site) {
        BoundingBox box = site.bounds();
        List<ChunkPos> chunks = new ArrayList<>();
        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                chunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }
        return chunks;
    }

    /** Whether every chunk the site's bounds touch is loaded, read without loading any. */
    static boolean loaded(ServerLevel level, StructureSite site) {
        return chunksOf(site).stream()
                .allMatch(chunk -> level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null);
    }

    /** How many of each block the site's bounds hold now. */
    static Map<Block, Integer> census(ServerLevel level, StructureSite site) {
        Map<Block, Integer> found = new HashMap<>();
        BoundingBox box = site.bounds();
        for (BlockPos pos :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            found.merge(level.getBlockState(pos).getBlock(), 1, Integer::sum);
        }
        return found;
    }

    /** The census by registry id, without air. */
    private static String describe(Map<Block, Integer> found) {
        Map<String, Integer> byId = new TreeMap<>();
        found.forEach((block, count) -> {
            if (!block.defaultBlockState().isAir()) {
                byId.put(BuiltInRegistries.BLOCK.getKey(block).toString(), count);
            }
        });
        return byId.toString();
    }

    /**
     * Every condition of a standing structure that does not hold yet, as text; empty when it stands. The blocks of {@link #minimum}
     * come first, then no fluid or gas, only its own Note in its own zone, and a lit candle beside it.
     */
    private static List<String> unmet(ServerLevel level, StructureSite site, Map<Block, Integer> found) {
        LayerChain.Band band = TestLayers.band(level, site.kind().layer());
        return unmet(level, band.minY(), band.height(), site, found);
    }

    /** {@link #unmet(ServerLevel, StructureSite, Map)} for a layer that spans {@code height} blocks from {@code minY} in its level (a band of the campaign world). */
    static List<String> unmet(ServerLevel level, int minY, int height, StructureSite site, Map<Block, Integer> found) {
        List<String> unmet = new ArrayList<>();
        for (Map.Entry<Block, Integer> expected :
                minimum(site.kind(), site.height()).entrySet()) {
            int count = found.getOrDefault(expected.getKey(), 0);
            if (count < expected.getValue()) {
                unmet.add(BuiltInRegistries.BLOCK.getKey(expected.getKey()) + " " + count + "/" + expected.getValue());
            }
        }
        String fluid = fluidOrGasAt(level, site, null);
        if (fluid != null) {
            unmet.add(fluid);
        }
        Map<Integer, BlockPos> notes = new HashMap<>();
        BoundingBox box = site.bounds();
        for (BlockPos pos :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.is(HandbookRegistry.NOTE)) {
                notes.put(state.getValue(NoteBlock.NOTE), pos.immutable());
            }
        }
        for (BlockPos pos :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockState(pos).is(AmbienceRegistry.COMPANY_HORN)) {
                String unsupported = hornSupportProblem(level, pos);
                if (unsupported != null) {
                    unmet.add(unsupported);
                }
            }
        }
        if (NOTES.containsKey(site.kind())) {
            int number = NOTES.get(site.kind());
            if (!notes.keySet().equals(Set.of(number))) {
                unmet.add(String.format("Notes %s, expected only N%02d", notes.keySet(), number));
            } else {
                int zone = Zones.index(
                        minY,
                        height,
                        Objects.requireNonNull(notes.get(number), "notes.get(number) is absent")
                                .getY());
                if (zone != site.kind().zone()) {
                    unmet.add(String.format(
                            "N%02d is in zone %d, not %d",
                            number, zone, site.kind().zone()));
                }
                if (site.kind().layer() == 1) {
                    // The candle is one block across the structure from the Note, which is south when the structure
                    // runs east to west.
                    BlockPos note = notes.get(number);
                    BlockState candle = level.getBlockState(site.alongZ() ? note.east() : note.south());
                    if (!candle.is(Blocks.CANDLE) || !candle.getValue(CandleBlock.LIT)) {
                        unmet.add("no lit candle beside the Note: " + candle);
                    }
                }
            }
        } else if (!notes.isEmpty()) {
            unmet.add(String.format("Notes %s but should hold none", notes.keySet()));
        }
        return unmet;
    }

    /** Whether each chunk of the site is loaded, and as what, read without loading any: a chunk that is not a {@code LevelChunk} is a wrapper of a full one. */
    static String chunkStates(ServerLevel level, StructureSite site) {
        Map<ChunkPos, String> states = new LinkedHashMap<>();
        for (ChunkPos chunk : chunksOf(site)) {
            ChunkAccess loaded = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
            states.put(chunk, loaded == null ? "not loaded" : loaded.getClass().getSimpleName());
        }
        return states.toString();
    }

    @GameTest
    public void sitesAvoidTheConduitCasing(GameTestHelper helper) {
        int radius = ColonyTuning.DEFAULT.conduitRadius();
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            LayerChain.Band levelBand = TestLayers.band(level, layer);
            for (StructureKind kind : StructureKind.inLayer(layer)) {
                StructureSite free = StructureSite.in(
                        level.getSeed(), kind, levelBand.minY(), levelBand.height(), CELL, CELL, FAR_CONDUIT);
                // A Conduit through the middle of the site it would have had.
                BlockPos blocking = free.origin();
                StructureSite moved = StructureSite.in(
                        level.getSeed(), kind, levelBand.minY(), levelBand.height(), CELL, CELL, blocking);
                BoundingBox box = moved.bounds();
                if (box.minX() <= blocking.getX() + radius
                        && box.maxX() >= blocking.getX() - radius
                        && box.minZ() <= blocking.getZ() + radius
                        && box.maxZ() >= blocking.getZ() - radius) {
                    throw failure(
                            helper, "%s still meets the casing round %s: %s", kind, blocking.toShortString(), box);
                }
                if (moved.equals(free)) {
                    throw failure(helper, "%s did not move off a Conduit through its centre", kind);
                }
                if (!moved.equals(StructureSite.in(
                        level.getSeed(), kind, levelBand.minY(), levelBand.height(), CELL, CELL, blocking))) {
                    throw failure(helper, "%s is not the same when asked twice for the same Conduit", kind);
                }
                if (box.minX() < CELL * SPACING
                        || box.maxX() >= (CELL + 1) * SPACING
                        || box.minZ() < CELL * SPACING
                        || box.maxZ() >= (CELL + 1) * SPACING) {
                    throw failure(helper, "%s moved out of its cell: %s", kind, box);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 600)
    public void aStructureSealsItsShellOfFluidAndGasBeforeTheCarve(GameTestHelper helper) {
        plantAndSeal(helper, 2, StructureKind.RAILS, SEAL_CELL, (state, pos) -> {});
    }

    /**
     * In layer 1 a sealed pocket of the shell is the rock of its stratum (#1277), not the layer's packed shale: the
     * Deep Claim's shaft is sealed in black shale, so no brown block shows in its wall. Rows near a contact may be
     * either rock, as the wall round them is.
     */
    @GameTest(maxTicks = 600)
    public void aLayerOneStructureSealsItsShellInItsStratumsRock(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        LayerChain.Band band = TestLayers.band(level, 1);
        plantAndSeal(helper, 1, StructureKind.DEEP_SHAFT, LAYER_1_SEAL_CELL, (state, pos) -> {
            Block rock = LayerBlocks.stratum(band, pos.getY());
            boolean nearContact = LayerBlocks.stratum(band, pos.getY() + CONTACT_REACH) != rock
                    || LayerBlocks.stratum(band, pos.getY() - CONTACT_REACH) != rock;
            if (!state.is(LayerBlocks.GROUND) || (!nearContact && !state.is(rock))) {
                throw failure(helper, "the seal at %s is %s, expected %s", pos.toShortString(), state, rock);
            }
        });
    }

    /** What a test asks of a planted block once the structure has sealed it. */
    private interface SealCheck {
        void check(BlockState state, BlockPos pos);
    }

    /**
     * Plants lava in the wall beside a {@code kind} structure's hollow (its shell) and gas in its roof, in the chunk of
     * the site's centre before the structure is drawn, then makes the chunk full: every planted block must be sealed,
     * and {@code sealed} judges what each in the shell became.
     */
    private static void plantAndSeal(GameTestHelper helper, int layer, StructureKind kind, int cell, SealCheck sealed) {
        ServerLevel level = TestLayers.level(helper, layer);
        LayerChain.Band levelBand = TestLayers.band(level, layer);
        StructureSite site = StructureSite.in(
                level.getSeed(), kind, levelBand.minY(), levelBand.height(), cell, cell, conduit(helper));
        // The chunk of the site's centre, stopped before it is full: the zone fill has run, and the structure is not
        // drawn yet.
        ChunkPos chunkPos = ChunkPos.containing(site.origin());
        // sync-load: the case needs a proto chunk stopped before FULL, which only a synchronous request for that
        // status gives
        ChunkAccess proto = level.getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FEATURES);
        if (proto instanceof ImposterProtoChunk) {
            throw failure(
                    helper,
                    "%s: chunk already FULL, another test loaded it; writes would be dropped (got %s)",
                    chunkPos,
                    proto.getClass().getSimpleName());
        }
        BlockState lava = Blocks.LAVA.defaultBlockState();
        BlockState gas = HazardBlocks.GAS_POCKET.defaultBlockState();
        // Lava in the wall beside the hollow (the shell, outside the bounds) and gas in its roof, within this chunk.
        int side = chunkPos.equals(ChunkPos.containing(at(site, 0, 1, 3))) ? 3 : -3;
        List<BlockPos> planted = new ArrayList<>();
        for (int u = -3; u <= 3; u++) {
            planted.add(at(site, u, 1, side));
            planted.add(at(site, u, site.height(), 0));
        }
        planted.removeIf(pos -> !chunkPos.equals(ChunkPos.containing(pos)));
        if (planted.size() < 2) {
            throw failure(helper, "only %d of the planted blocks lie in %s", planted.size(), chunkPos);
        }
        for (BlockPos pos : planted) {
            proto.setBlockState(pos, pos.getY() == site.origin().getY() + site.height() ? gas : lava, 0);
        }
        BlockPos control = columnClearOf(site, chunkPos);
        proto.setBlockState(control, lava, 0);
        // sync-load: the case completes the proto chunk above in the same tick as its planted blocks
        level.getChunk(chunkPos.x(), chunkPos.z(), ChunkStatus.FULL);
        helper.succeedWhen(() -> {
            for (BlockPos pos : planted) {
                BlockState state = level.getBlockState(pos);
                if (!state.getFluidState().isEmpty() || state.is(HazardBlocks.GAS_POCKET)) {
                    throw failure(helper, "%s at %s was not sealed", state, pos.toShortString());
                }
            }
            // The lava away from the structure is still there, so the planted blocks did reach the full chunk.
            if (!level.getBlockState(control).is(Blocks.LAVA)) {
                throw failure(
                        helper,
                        "the lava planted at %s clear of the structure is %s: the plant did not survive the chunk becoming full",
                        control.toShortString(),
                        level.getBlockState(control));
            }
            requireNoFluidOrGas(helper, level, site, chunkPos);
            // The shell only: inside the bounds the draw may set a block of its own over a sealed one.
            for (BlockPos pos : planted) {
                if (!site.bounds().isInside(pos)) {
                    sealed.check(level.getBlockState(pos), pos);
                }
            }
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void aChunkLoadedFromDiskIsNotDrawnInto(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band levelBand = TestLayers.band(level, 2);
        StructureSite site = StructureSite.in(
                level.getSeed(),
                StructureKind.WRECK,
                levelBand.minY(),
                levelBand.height(),
                DISK_CELL,
                DISK_CELL,
                conduit(helper));
        FarChunks.force(helper, level, site.origin().getX() >> 4, site.origin().getZ() >> 4);
        BlockPos floor = site.origin().below();
        helper.succeedWhen(() -> {
            LevelChunk chunk = loadedChunk(helper, level, site);
            if (level.getBlockState(floor).isAir()) {
                throw failure(helper, "the floor of the wreck bay at %s was not drawn", floor.toShortString());
            }
            // room-carver: tests the draw on a floor block of the wreck bay: one block, not a room
            level.setBlock(floor, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(level, chunk, false);
            if (!level.getBlockState(floor).isAir()) {
                throw failure(
                        helper,
                        "a chunk that was not newly generated was drawn into: %s at %s",
                        level.getBlockState(floor),
                        floor.toShortString());
            }
            // The same call for a new chunk draws it again, so the check above could have failed.
            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(level, chunk, true);
            if (level.getBlockState(floor).isAir()) {
                throw failure(
                        helper, "a newly generated chunk was not drawn: the floor at %s is air", floor.toShortString());
            }
        });
    }

    /**
     * Why the horn at {@code horn} floats; null when the block above its chain (if any) is a sturdy roof, or a beam that is joined to a
     * post of iron that stands on a sturdy floor.
     */
    static @Nullable String hornSupportProblem(ServerLevel level, BlockPos horn) {
        BlockPos top = horn.above();
        while (level.getBlockState(top).is(Blocks.IRON_CHAIN)) {
            top = top.above();
        }
        BlockState above = level.getBlockState(top);
        if (!above.isFaceSturdy(level, top, Direction.DOWN)) {
            return String.format(
                    "the horn at %s hangs from %s at %s, which is not sturdy",
                    horn.toShortString(), above, top.toShortString());
        }
        if (!above.is(Blocks.IRON_BLOCK)) {
            return null;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos post = top.relative(side);
            while (level.getBlockState(post).is(Blocks.IRON_BLOCK)) {
                post = post.below();
            }
            if (!post.equals(top.relative(side)) && level.getBlockState(post).isFaceSturdy(level, post, Direction.UP)) {
                return null;
            }
        }
        return String.format("the beam over the horn at %s has no post to a floor", horn.toShortString());
    }

    /** A punch clock's chunk, drawn once {@link #requireFull} stops throwing. */
    private record PunchClockDraw(StructureSite site, ServerLevel level, ChunkPos chunk, AtomicBoolean edited) {
        void requireFull(GameTestHelper helper) {
            if (!edited.get() || level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
                throw failure(helper, "the chunk %s is not drawn yet", chunk);
            }
        }
    }

    /**
     * The punch clock of {@code cell}, its chunk stopped before it is full, with the column over the horn cleared to air up to
     * {@code clearTo} above the floor and, when {@code ceiling} is given, stone at that height; then the chunk fills, which draws it.
     * Both steps run on the worker threads: poll {@link PunchClockDraw#requireFull} before reading the draw.
     */
    private static PunchClockDraw drawPunchClock(
            GameTestHelper helper, int cell, int clearTo, @Nullable Integer ceiling) {
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band band = TestLayers.band(level, 2);
        StructureSite site = StructureSite.in(
                level.getSeed(), StructureKind.PUNCH_CLOCK, band.minY(), band.height(), cell, cell, conduit(helper));
        if (site.origin().getY() + StructureKind.HORN_HEIGHT + StructureKind.HORN_REACH + 1 > band.maxY()) {
            throw failure(helper, "the band tops out at %d, too low to test a horn's reach", band.maxY());
        }
        ChunkPos chunkPos = ChunkPos.containing(site.origin());
        AtomicBoolean edited = new AtomicBoolean();
        FarChunks.whenReachedStatus(
                helper, List.of(new FarChunks.At(level, chunkPos.x(), chunkPos.z())), ChunkStatus.FEATURES, reached -> {
                    ChunkAccess proto = reached.get(0);
                    if (proto instanceof ImposterProtoChunk) {
                        throw failure(helper, "%s: chunk already FULL, another test loaded it", chunkPos);
                    }
                    for (int y = StructureKind.HORN_HEIGHT + 1; y <= clearTo; y++) {
                        proto.setBlockState(at(site, 0, y, 0), Blocks.AIR.defaultBlockState(), 0);
                    }
                    if (ceiling != null) {
                        proto.setBlockState(at(site, 0, ceiling, 0), Blocks.STONE.defaultBlockState(), 0);
                    }
                    FarChunks.force(helper, level, chunkPos.x(), chunkPos.z());
                    edited.set(true);
                });
        return new PunchClockDraw(site, level, chunkPos, edited);
    }

    /** A roof {@code over} blocks above the horn: a chain of {@code over - 1} between, which is none when the roof is right over it. */
    private void hornHangsFromRoof(GameTestHelper helper, int cell, int over) {
        ServerLevel level = TestLayers.level(helper, 2);
        int ceiling = StructureKind.HORN_HEIGHT + over;
        PunchClockDraw draw = drawPunchClock(helper, cell, ceiling + 3, ceiling);
        StructureSite site = draw.site();
        BlockPos horn = at(site, 0, StructureKind.HORN_HEIGHT, 0);
        helper.succeedWhen(() -> {
            draw.requireFull(helper);
            expect(helper, level, horn, AmbienceRegistry.COMPANY_HORN);
            for (int y = StructureKind.HORN_HEIGHT + 1; y < ceiling; y++) {
                expect(helper, level, at(site, 0, y, 0), Blocks.IRON_CHAIN);
            }
            expect(helper, level, at(site, 0, ceiling, 0), Blocks.STONE);
            expect(helper, level, at(site, 0, StructureKind.HORN_HEIGHT - 1, 0), Blocks.AIR);
            requireSupported(helper, level, horn);
        });
    }

    private static void requireSupported(GameTestHelper helper, ServerLevel level, BlockPos horn) {
        String problem = hornSupportProblem(level, horn);
        if (problem != null) {
            throw failure(helper, "%s", problem);
        }
    }

    @GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void aHornUnderADistantRoofHangsOnAChainFromIt(GameTestHelper helper) {
        hornHangsFromRoof(helper, HORN_CEILING_CELL, 10);
    }

    @GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void aHornWithTheRoofRightOverItKeepsTheRoofAndHasNoChain(GameTestHelper helper) {
        hornHangsFromRoof(helper, HORN_ADJACENT_CELL, 1);
    }

    @GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void aHornWithTheRoofTwoOverItHangsOnOneChain(GameTestHelper helper) {
        hornHangsFromRoof(helper, HORN_ONE_CHAIN_CELL, 2);
    }

    @GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void aHornWithNoRoofInReachHangsFromABeamOnAPost(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 2);
        PunchClockDraw draw =
                drawPunchClock(helper, HORN_OPEN_CELL, StructureKind.HORN_HEIGHT + StructureKind.HORN_REACH + 1, null);
        BlockPos horn = at(draw.site(), 0, StructureKind.HORN_HEIGHT, 0);
        helper.succeedWhen(() -> {
            draw.requireFull(helper);
            expect(helper, level, horn, AmbienceRegistry.COMPANY_HORN);
            expect(helper, level, horn.above(), Blocks.IRON_BLOCK);
            expect(helper, level, horn.above(2), Blocks.AIR);
            expect(helper, level, horn.below(), Blocks.AIR);
            requireSupported(helper, level, horn);
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void theSupportCheckSeesAFloatingHornAndASupportedOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = new BlockPos(44_008, OPEN_AIR_Y, 44_008);
        FarChunks.whenBlocksLoaded(
                helper, FarChunks.overBlocks(level, at.getX(), at.getZ(), at.getX(), at.getZ()), () -> {
                    BlockState horn = AmbienceRegistry.COMPANY_HORN.defaultBlockState();
                    BlockState chain = Blocks.IRON_CHAIN.defaultBlockState();
                    BlockState iron = Blocks.IRON_BLOCK.defaultBlockState();
                    BlockState stone = Blocks.STONE.defaultBlockState();
                    BlockState air = Blocks.AIR.defaultBlockState();
                    level.setBlock(at, horn, Block.UPDATE_CLIENTS);
                    expectFloating(helper, level, at, "a horn in open air");
                    level.setBlock(at.above(), chain, Block.UPDATE_CLIENTS);
                    expectFloating(helper, level, at, "a horn on a chain that ends in air");
                    level.setBlock(at.above(2), stone, Block.UPDATE_CLIENTS);
                    requireSupported(helper, level, at);
                    level.setBlock(at.above(), air, Block.UPDATE_CLIENTS);
                    expectFloating(helper, level, at, "a horn with a dent in the roof right over it");
                    level.setBlock(at.above(), stone, Block.UPDATE_CLIENTS);
                    requireSupported(helper, level, at);
                    level.setBlock(at.above(2), air, Block.UPDATE_CLIENTS);
                    level.setBlock(at.above(), iron, Block.UPDATE_CLIENTS);
                    expectFloating(helper, level, at, "a beam with no post");
                    level.setBlock(at.above().east(), iron, Block.UPDATE_CLIENTS);
                    expectFloating(helper, level, at, "a beam on a post that ends in air");
                    for (int down = 0; down <= 2; down++) {
                        level.setBlock(at.above().east().below(down), iron, Block.UPDATE_CLIENTS);
                    }
                    level.setBlock(at.above().east().below(3), stone, Block.UPDATE_CLIENTS);
                    requireSupported(helper, level, at);
                    helper.succeed();
                });
    }

    private static void expectFloating(GameTestHelper helper, ServerLevel level, BlockPos horn, String what) {
        if (hornSupportProblem(level, horn) == null) {
            throw failure(helper, "%s was reported as supported", what);
        }
    }

    /** A cell of its own for the wreck bay's locker, which no other test draws. */
    private static final int LOCKER_CELL = 10;

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void aWreckBayHoldsTheCrewsLockerInItsCornerClearOfThePodAndTheRail(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band levelBand = TestLayers.band(level, 2);
        StructureSite site = StructureSite.in(
                level.getSeed(),
                StructureKind.WRECK,
                levelBand.minY(),
                levelBand.height(),
                LOCKER_CELL,
                LOCKER_CELL,
                conduit(helper));
        StructureSite again = StructureSite.in(
                level.getSeed(),
                StructureKind.WRECK,
                levelBand.minY(),
                levelBand.height(),
                LOCKER_CELL,
                LOCKER_CELL,
                conduit(helper));
        // The locker is at u 5, v -5 of the bay (StructureKind.WRECK); the bay turns along X or Z with the site.
        BlockPos locker = site.origin().offset(site.alongZ() ? -5 : 5, 0, site.alongZ() ? 5 : -5);
        if (!site.equals(again)
                || !locker.equals(again.origin().offset(site.alongZ() ? -5 : 5, 0, site.alongZ() ? 5 : -5))) {
            throw failure(helper, "the same seed gave two different sites: %s and %s", site, again);
        }
        BlockPos pod = site.origin();
        List<BlockPos> rail = new ArrayList<>();
        for (int u = -6; u <= -3; u++) {
            rail.add(site.origin().offset(site.alongZ() ? 0 : u, 0, site.alongZ() ? u : 0));
        }
        if (locker.equals(pod) || rail.contains(locker) || locker.distSqr(pod) < 9) {
            throw failure(helper, "the locker at %s is on the pod's spot %s or the rail %s", locker, pod, rail);
        }
        FarChunks.whenBlocksLoaded(
                helper, FarChunks.overBlocks(level, locker.getX(), locker.getZ(), locker.getX(), locker.getZ()), () -> {
                    BlockState state = level.getBlockState(locker);
                    if (!state.is(Blocks.BARREL)) {
                        throw failure(
                                helper, "the corner of the wreck bay at %s holds %s, not the locker", locker, state);
                    }
                    if (!(level.getBlockEntity(locker) instanceof RandomizableContainerBlockEntity barrel)
                            || barrel.getLootTable() == null
                            || !barrel.getLootTable().identifier().getPath().equals("chests/wreck_locker")) {
                        throw failure(helper, "the locker at %s has no loot table of the wreck locker", locker);
                    }
                    if (barrel.getLootTableSeed() != site.seed()) {
                        throw failure(
                                helper,
                                "the locker's loot seed is %s, the site's is %s",
                                barrel.getLootTableSeed(),
                                site.seed());
                    }
                    helper.succeed();
                });
    }

    /** A cell of its own for an ordinary wreck bay, which has no phonograph. */
    private static final int WRECK_PHONOGRAPH_CELL = 11;

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void anOrdinaryWreckBayHasNoCabinPhonographThatIsPROSPECTOR0002sAlone(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band levelBand = TestLayers.band(level, 2);
        StructureSite site = StructureSite.in(
                level.getSeed(),
                StructureKind.WRECK,
                levelBand.minY(),
                levelBand.height(),
                WRECK_PHONOGRAPH_CELL,
                WRECK_PHONOGRAPH_CELL,
                conduit(helper));
        if (LayerStructures.prospector(level).filter(site::equals).isPresent()) {
            throw failure(helper, "cell %d is the Prospector's own site", WRECK_PHONOGRAPH_CELL);
        }
        // The story bay's phonograph is at u 5, v 5, the same offset whichever way the bay turns (CampaignContentTest
        // counts it).
        BlockPos corner = site.origin().offset(5, 0, 5);
        FarChunks.whenBlocksLoaded(
                helper,
                FarChunks.overBlocks(level, site.origin().getX(), site.origin().getZ(), corner.getX(), corner.getZ()),
                () -> {
                    if (level.getBlockState(site.origin().offset(0, -1, 0)).isAir()) {
                        throw failure(
                                helper,
                                "the bay at %s was not drawn yet",
                                site.origin().toShortString());
                    }
                    BlockState state = level.getBlockState(corner);
                    if (state.is(PhonographRegistry.WRECK_PHONOGRAPH)) {
                        throw failure(helper, "an ordinary wreck bay at %s holds a cabin phonograph", corner);
                    }
                    helper.succeed();
                });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void theConduitCasingInsideASiteSurvivesTheDraw(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band levelBand = TestLayers.band(level, 2);
        StructureSite site = StructureSite.in(
                level.getSeed(),
                StructureKind.WRECK,
                levelBand.minY(),
                levelBand.height(),
                CASING_CELL,
                CASING_CELL,
                conduit(helper));
        FarChunks.force(helper, level, site.origin().getX() >> 4, site.origin().getZ() >> 4);
        helper.succeedWhen(() -> {
            LevelChunk chunk = loadedChunk(helper, level, site);
            // A casing block where the draw carves air.
            chunk.setBlockState(site.origin(), ColonyBlocks.CONDUIT.defaultBlockState(), 0);
            ServerChunkEvents.CHUNK_LOAD.invoker().onChunkLoad(level, chunk, true);
            BlockState after = level.getBlockState(site.origin());
            // room-carver: puts back the one air block the draw carved; not a room
            chunk.setBlockState(site.origin(), Blocks.AIR.defaultBlockState(), 0);
            if (!after.is(ColonyBlocks.CONDUIT)) {
                throw failure(
                        helper,
                        "the casing block at %s became %s",
                        site.origin().toShortString(),
                        after);
            }
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void thePiecesOfAStructureAcrossChunkBordersMeet(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band levelBand = TestLayers.band(level, 2);
        StructureKind kind = StructureKind.RAILS;
        StructureSite site = StructureSite.in(
                level.getSeed(), kind, levelBand.minY(), levelBand.height(), SEAM_CELL, SEAM_CELL, conduit(helper));
        for (ChunkPos chunk : chunksOf(site)) {
            FarChunks.force(helper, level, chunk.x(), chunk.z());
        }
        helper.succeedWhen(() -> {
            if (!loaded(level, site)) {
                throw failure(
                        helper,
                        "the chunks of the rail line at %s are not loaded yet",
                        site.origin().toShortString());
            }
            int seams = 0;
            for (int u = -RAILS_HALF_U; u <= RAILS_HALF_U; u++) {
                if (u > -RAILS_HALF_U
                        && !ChunkPos.containing(at(site, u, 0, 0)).equals(ChunkPos.containing(at(site, u - 1, 0, 0)))) {
                    seams++;
                }
                for (int v = -RAILS_HALF_V; v <= RAILS_HALF_V; v++) {
                    expect(helper, level, at(site, u, -1, v), Blocks.COARSE_DIRT);
                }
                for (int y = 1; y <= 2; y++) {
                    expect(helper, level, at(site, u, y, 0), Blocks.AIR);
                }
                if (Math.floorMod(u, 8) == 0) {
                    for (int v : new int[] {-2, 2}) {
                        for (int y = 0; y <= 2; y++) {
                            expect(helper, level, at(site, u, y, v), Blocks.OAK_LOG);
                        }
                    }
                }
            }
            // The line crosses a border, so that the check above covered a seam.
            if (seams < 2) {
                throw failure(
                        helper,
                        "the rail line at %s crosses no chunk border",
                        site.origin().toShortString());
            }
        });
    }

    /**
     * The Kells' shaft (#716) hangs off the end of the rail line of its cell and runs down to the first block above the crust, and
     * through the crust into layer 3 (#972).
     * Its hollow is a clear 2 x 2, its top meets the line's last column through a drift with a rail, and its lava is the
     * gaps: each is one lava block in a ring of slag brick, backed by one more, with a bulkhead of brick across the hollow at its row, and each has {@value StructureKind#KELLS_RESERVE_PER_GAP} bricks in the reserve at the head.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void theKellsShaftRunsFromTheRailLineToTheCrustAndHasGapsInItsLining(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band levelBand = TestLayers.band(level, 2);
        BlockPos conduit = conduit(helper);
        StructureSite rails = StructureSite.in(
                level.getSeed(),
                StructureKind.RAILS,
                levelBand.minY(),
                levelBand.height(),
                KELLS_CELL,
                KELLS_CELL,
                conduit);
        StructureSite shaft = StructureSite.in(
                level.getSeed(),
                StructureKind.KELLS_SHAFT,
                levelBand.minY(),
                levelBand.height(),
                KELLS_CELL,
                KELLS_CELL,
                conduit);
        if (!at(rails, StructureKind.KELLS_OFFSET, 0, 0)
                        .atY(shaft.origin().getY())
                        .equals(shaft.origin())
                || shaft.alongZ() != rails.alongZ()) {
            throw failure(
                    helper,
                    "the shaft at %s is not %d blocks along the rail line at %s",
                    shaft.origin().toShortString(),
                    StructureKind.KELLS_OFFSET,
                    rails.origin().toShortString());
        }
        if (shaft.origin().getY() != levelBand.minY() + LayerTuning.DEFAULT.crustThickness()) {
            throw failure(
                    helper,
                    "the shaft's floor row is y=%d, not the first block above the crust",
                    shaft.origin().getY());
        }
        if (shaft.origin().getY() + shaft.height() - 3 != rails.origin().getY()) {
            throw failure(
                    helper,
                    "the shaft's drift is at y=%d but the rail line's floor is at y=%d",
                    shaft.origin().getY() + shaft.height() - 3,
                    rails.origin().getY());
        }
        for (StructureSite site : List.of(rails, shaft)) {
            for (ChunkPos chunk : chunksOf(site)) {
                FarChunks.force(helper, level, chunk.x(), chunk.z());
            }
        }
        FarChunks.Deadline deadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            if (!loaded(level, rails) || !loaded(level, shaft)) {
                deadline.await(helper, level, false, () -> "the chunks of the Kells' shaft and its rails did not load");
                return;
            }
            List<String> unmet = kellsUnmet(level, rails, shaft);
            if (!unmet.isEmpty()) {
                deadline.await(
                        helper,
                        level,
                        false,
                        () -> String.format(
                                "the Kells' shaft at %s did not settle: %s",
                                shaft.origin().toShortString(), unmet));
                return;
            }
            helper.succeed();
        });
    }

    /**
     * No gap of the Kells' shaft is in another's wall: across 100 cells the rows of a shaft's gaps are 2 to 4, rise by at
     * least 3 and stay clear of the drift at the top and the crust at the bottom.
     */
    @GameTest
    public void theGapsOfTheKellsShaftAreAtLeastThreeRowsApartInEveryCell(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band levelBand = TestLayers.band(level, 2);
        BlockPos conduit = conduit(helper);
        for (int cellX = 0; cellX < 10; cellX++) {
            for (int cellZ = 0; cellZ < 10; cellZ++) {
                StructureSite shaft = StructureSite.in(
                        level.getSeed(),
                        StructureKind.KELLS_SHAFT,
                        levelBand.minY(),
                        levelBand.height(),
                        cellX,
                        cellZ,
                        conduit);
                int[] rows = StructureKind.kellsGapRows(shaft);
                if (rows.length < 2 || rows.length > 4) {
                    throw failure(
                            helper, "cell %d, %d (depth %d) has %d gaps", cellX, cellZ, shaft.height(), rows.length);
                }
                for (int k = 0; k < rows.length; k++) {
                    if (k > 0 && rows[k] - rows[k - 1] < 3) {
                        throw failure(
                                helper,
                                "cell %d, %d (depth %d): gaps at rows %s are less than 3 apart",
                                cellX,
                                cellZ,
                                shaft.height(),
                                Arrays.toString(rows));
                    }
                    if (rows[k] < 3 || rows[k] > shaft.height() - 8) {
                        throw failure(
                                helper,
                                "cell %d, %d (depth %d): a gap at row %d is outside the shaft's band",
                                cellX,
                                cellZ,
                                shaft.height(),
                                rows[k]);
                    }
                }
            }
        }
        helper.succeed();
    }

    private static StructureKind.RingSide ringSide(int u, int v) {
        return StructureKind.kellsRingSides().stream()
                .filter(side -> side.u() == u && side.v() == v)
                .findFirst()
                .orElseThrow();
    }

    /** What the Kells' shaft lacks, as text; empty when it stands as designed. */
    private static List<String> kellsUnmet(ServerLevel level, StructureSite rails, StructureSite shaft) {
        List<String> unmet = new ArrayList<>();
        int top = shaft.height() - 1;
        // The gaps: the side cells of the ring that hold lava.
        List<int[]> gaps = new ArrayList<>();
        for (int y = 0; y <= top; y++) {
            for (StructureKind.RingSide side : StructureKind.kellsRingSides()) {
                if (level.getBlockState(at(shaft, side.u(), y, side.v())).is(Blocks.LAVA)) {
                    gaps.add(new int[] {side.u(), y, side.v()});
                }
            }
        }
        // The hollow is clear but for a bulkhead of slag brick across it at the row of each gap, with a hole
        // beside the gap and a lava block in the cell under the hole.
        for (int y = 0; y <= top; y++) {
            for (int u = -1; u <= 0; u++) {
                for (int v = -1; v <= 0; v++) {
                    String expected = "air";
                    for (int[] gap : gaps) {
                        StructureKind.RingSide side = ringSide(gap[0], gap[2]);
                        boolean hole = u == side.holeU() && v == side.holeV();
                        if (y == gap[1]) {
                            expected = hole ? "air" : "slag";
                        } else if (y == gap[1] - 1 && hole) {
                            expected = "lava";
                        }
                    }
                    BlockState state = level.getBlockState(at(shaft, u, y, v));
                    boolean right = switch (expected) {
                        case "slag" -> state.is(SlagBrick.BLOCK);
                        case "lava" -> state.is(Blocks.LAVA);
                        default -> state.isAir();
                    };
                    if (!right) {
                        unmet.add(String.format(
                                "the hollow at (%d, %d, %d) holds %s, expected %s", u, y, v, state, expected));
                        return unmet;
                    }
                }
            }
        }
        if (gaps.size() < 2 || gaps.size() > 4) {
            unmet.add(gaps.size() + " gaps, expected 2 to 4");
        }
        // The layer 3 end: the crust under the hollow is broken through.
        for (int y = -LayerTuning.DEFAULT.crustThickness(); y < 0; y++) {
            for (int u = -1; u <= 0; u++) {
                for (int v = -1; v <= 0; v++) {
                    BlockState state = level.getBlockState(at(shaft, u, y, v));
                    if (!state.isAir()) {
                        unmet.add(String.format(
                                "the crust under the hollow at (%d, %d, %d) is %s, not broken through",
                                u, y, v, state));
                        return unmet;
                    }
                }
            }
        }
        for (int[] gap : gaps) {
            // The lava is backed by one more block, away from the hollow.
            StructureKind.RingSide side = ringSide(gap[0], gap[2]);
            int backU = side.u() + side.outU();
            int backV = side.v() + side.outV();
            if (!level.getBlockState(at(shaft, backU, gap[1], backV)).is(Blocks.LAVA)) {
                unmet.add("the gap at y " + gap[1] + " has no lava behind it");
            }
            // The rest of the ring, one row each way, is slag brick.
            for (int y = gap[1] - 1; y <= gap[1] + 1; y++) {
                for (int u = -2; u <= 1; u++) {
                    for (int v = -2; v <= 1; v++) {
                        boolean inside = u >= -1 && u <= 0 && v >= -1 && v <= 0;
                        boolean theGap = u == gap[0] && y == gap[1] && v == gap[2];
                        if (!inside
                                && !theGap
                                && !level.getBlockState(at(shaft, u, y, v)).is(SlagBrick.BLOCK)) {
                            unmet.add(String.format(
                                    "the lining at (%d, %d, %d) is %s, not slag brick",
                                    u, y, v, level.getBlockState(at(shaft, u, y, v))));
                            return unmet;
                        }
                    }
                }
            }
        }
        // The head: the drift is open from the last column to the lip, with a rail, and the reserve is in its walls.
        for (int y = top - 2; y <= top; y++) {
            for (int u = -4; u <= -2; u++) {
                for (int v = -1; v <= 0; v++) {
                    BlockState state = level.getBlockState(at(shaft, u, y, v));
                    if (!state.isAir() && !state.is(Blocks.RAIL)) {
                        unmet.add(String.format("the drift at (%d, %d, %d) holds %s", u, y, v, state));
                        return unmet;
                    }
                }
            }
        }
        if (!level.getBlockState(at(shaft, -3, top - 2, 0)).is(Blocks.RAIL)) {
            unmet.add("no rail in the drift");
        }
        BlockState lineEnd = level.getBlockState(at(rails, RAILS_HALF_U, 0, 0));
        if (!lineEnd.isAir() && !lineEnd.is(Blocks.RAIL)) {
            unmet.add("the line's last column holds " + lineEnd);
        }
        int reserve = 0;
        for (int u = -3; u <= -2; u++) {
            for (int y = top - 2; y <= top; y++) {
                for (int v : new int[] {1, -2}) {
                    if (level.getBlockState(at(shaft, u, y, v)).is(SlagBrick.BLOCK)) {
                        reserve++;
                    }
                }
            }
        }
        if (reserve != gaps.size() * StructureKind.KELLS_RESERVE_PER_GAP) {
            unmet.add(reserve + " bricks in the reserve for " + gaps.size() + " gaps");
        }
        BoundingBox box = shaft.bounds();
        for (BlockPos pos :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockState(pos).is(HazardBlocks.GAS_POCKET)) {
                unmet.add("a gas pocket at " + pos.toShortString());
                break;
            }
        }
        return unmet;
    }

    @GameTest
    public void theProspectorsSiteIsTheWreckSiteNearestTheConduit(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        BlockPos conduit = Colony.anchor(server, ColonyAnchor.CONDUIT)
                .orElseThrow(() -> failure(helper, "the colony was not built"));
        ServerLevel level = TestLayers.level(helper, 2);
        LayerChain.Band levelBand = TestLayers.band(level, 2);
        StructureSite prospector = LayerStructures.prospector(TestLayers.level(server, StructureKind.WRECK.layer()))
                .orElseThrow(() -> failure(helper, "no Prospector site"));
        StructureSite best = null;
        double bestDistance = Double.MAX_VALUE;
        int centreX = Math.floorDiv(conduit.getX(), SPACING);
        int centreZ = Math.floorDiv(conduit.getZ(), SPACING);
        for (int cellX = centreX - PROSPECTOR_CELLS; cellX <= centreX + PROSPECTOR_CELLS; cellX++) {
            for (int cellZ = centreZ - PROSPECTOR_CELLS; cellZ <= centreZ + PROSPECTOR_CELLS; cellZ++) {
                StructureSite site = StructureSite.in(
                        level.getSeed(),
                        StructureKind.WRECK,
                        levelBand.minY(),
                        levelBand.height(),
                        cellX,
                        cellZ,
                        conduit);
                double distance = Math.hypot(
                        site.origin().getX() - conduit.getX(), site.origin().getZ() - conduit.getZ());
                if (distance < bestDistance) {
                    best = site;
                    bestDistance = distance;
                }
            }
        }
        if (!prospector.equals(best)) {
            throw failure(
                    helper,
                    "the Prospector's site is %s but the nearest of %d cells is %s",
                    prospector,
                    2 * PROSPECTOR_CELLS + 1,
                    best);
        }
        Optional<Zones.Zone> zone = Zones.of(level, prospector.origin().getY());
        if (zone.isEmpty() || !zone.get().id().getPath().equals("prospectors_run")) {
            throw failure(helper, "the Prospector's site is in %s, not Prospector's Run", zone);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void theColonyHoldsNotesOneToFourAtTheirBuildings(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        ColonySite.Placed colony = Colony.placed(server).orElseThrow(() -> failure(helper, "the colony was not built"));
        BlockPos around = colony.center();
        FarChunks.whenBlocksLoaded(
                helper,
                FarChunks.overBlocks(
                        server.overworld(),
                        around.getX() - PAD_REACH,
                        around.getZ() - PAD_REACH,
                        around.getX() + PAD_REACH,
                        around.getZ() + PAD_REACH),
                () -> theColonyHoldsNotesOneToFour(helper, server, colony));
    }

    private static void theColonyHoldsNotesOneToFour(
            GameTestHelper helper, MinecraftServer server, ColonySite.Placed colony) {
        ServerLevel overworld = server.overworld();
        BlockPos centre = colony.center();
        Map<Integer, BlockPos> notes = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                centre.getX() - PAD_REACH,
                centre.getY(),
                centre.getZ() - PAD_REACH,
                centre.getX() + PAD_REACH,
                centre.getY() + PAD_HEIGHT,
                centre.getZ() + PAD_REACH)) {
            BlockState state = overworld.getBlockState(pos);
            if (state.is(HandbookRegistry.NOTE) && notes.put(state.getValue(NoteBlock.NOTE), pos.immutable()) != null) {
                throw failure(helper, "N%02d stands twice in the colony", state.getValue(NoteBlock.NOTE));
            }
        }
        if (!notes.keySet().equals(Set.of(1, 2, 3, 4))) {
            throw failure(helper, "the colony holds Notes %s, expected N01 to N04", notes.keySet());
        }
        Map<Integer, ColonyAnchor> buildings = Map.of(
                1,
                ColonyAnchor.PERSONNEL_OFFICE,
                2,
                ColonyAnchor.PAY_OFFICE,
                3,
                ColonyAnchor.CHAPEL_CANDLE,
                4,
                ColonyAnchor.CONTINUITY_OFFICE);
        buildings.forEach((number, anchor) -> {
            BlockPos at = TestColony.anchor(colony, anchor);
            double distance = Math.hypot(
                    Objects.requireNonNull(notes.get(number), "notes.get(number) is absent")
                                    .getX()
                            - at.getX(),
                    notes.get(number).getZ() - at.getZ());
            if (distance > NEAR_ANCHOR) {
                throw failure(
                        helper,
                        "N%02d is %.1f blocks from the %s, expected at most %.0f",
                        number,
                        distance,
                        anchor,
                        NEAR_ANCHOR);
            }
        });
        helper.succeed();
    }

    private static BlockPos conduit(GameTestHelper helper) {
        return Colony.anchor(server(helper), ColonyAnchor.CONDUIT)
                .orElseThrow(() -> failure(helper, "the colony was not built"));
    }

    /** The world position of a block given in the structure's own axes (see {@code StructurePlan}). */
    private static BlockPos at(StructureSite site, int u, int y, int v) {
        return site.origin().offset(site.alongZ() ? v : u, y, site.alongZ() ? u : v);
    }

    /** A block of the chunk at the height of the site that is at least 3 blocks clear of the site's bounds. */
    private static BlockPos columnClearOf(StructureSite site, ChunkPos chunk) {
        BoundingBox box = site.bounds();
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                if (x < box.minX() - 3 || x > box.maxX() + 3 || z < box.minZ() - 3 || z > box.maxZ() + 3) {
                    return new BlockPos(x, site.origin().getY(), z);
                }
            }
        }
        throw new IllegalStateException("every column of " + chunk + " is within 3 blocks of " + box);
    }

    private static void expect(GameTestHelper helper, ServerLevel level, BlockPos pos, Block block) {
        if (!level.getBlockState(pos).is(block)) {
            throw failure(helper, "%s at %s, expected %s", level.getBlockState(pos), pos.toShortString(), block);
        }
    }

    /** No fluid and no gas pocket in the site's bounds and the shell round them, limited to the blocks of {@code only}. */
    private static void requireNoFluidOrGas(
            GameTestHelper helper, ServerLevel level, StructureSite site, ChunkPos only) {
        String found = fluidOrGasAt(level, site, only);
        if (found != null) {
            throw failure(helper, "%s", found);
        }
    }

    /** The first fluid or gas pocket in the site's bounds and the shell round them (limited to the blocks of {@code only} when it is given) as text, or null. */
    private static @Nullable String fluidOrGasAt(ServerLevel level, StructureSite site, @Nullable ChunkPos only) {
        BoundingBox box = site.bounds();
        for (BlockPos pos : BlockPos.betweenClosed(
                box.minX() - 1, box.minY() - 1, box.minZ() - 1, box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1)) {
            if (only != null && !only.equals(ChunkPos.containing(pos))) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            // The Kells' shaft keeps lava in its gaps on purpose; only other fluid is wrong there.
            if (site.kind() == StructureKind.KELLS_SHAFT && state.is(Blocks.LAVA)) {
                continue;
            }
            if (!state.getFluidState().isEmpty() || state.is(HazardBlocks.GAS_POCKET)) {
                return String.format("%s holds %s at %s", site.kind(), state, pos.toShortString());
            }
        }
        return null;
    }

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }
}
