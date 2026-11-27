package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.layer.gen.CaveOreFeature;
import io.github.pkeppeler.deepcharter.layer.gen.ClaimShaftFeature;
import io.github.pkeppeler.deepcharter.layer.gen.StrataRule;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import io.github.pkeppeler.deepcharter.ore.ZoneFillFeature;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.surface.SurfaceWrites;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the Claim's features write (#1277), read from the blocks they change and not from where they plan to: a shaft
 * writes nothing outside its plan's bounds, a cave ore seam turns only layer rock on a cave face into an ore of its
 * zone's table, and the generated claim holds seams on cave faces and salvage caches on cave floors. Each placement
 * test runs the real feature into a generated chunk of {@code campaign_full} and compares every block of layer 1's band
 * before and after.
 */
public class ClaimFeaturesTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClaimFeaturesTest.class);
    private static final String FULL = "deepcharter:campaign_full";
    private static final int MAX_TICKS = 20_000;
    private static final CampaignBands.Band LAYER = CampaignBands.LAYER_1;

    /** Far from every other test's chunks: each placement gets a chunk of its own. */
    private static final int FIRST_CHUNK = 1500;

    private static final int SHAFT_TRIES = 96;
    private static final int SEAM_CHUNKS = 24;
    private static final int SEAM_TRIES_PER_CHUNK = 12;

    /**
     * The sample of generated claim that the seams and caches are counted in, and the pail's reach that marks a shaft's
     * crate.
     */
    private static final int SAMPLE_CHUNK = 60;

    private static final int SAMPLE_CHUNKS = 10;
    private static final int SHAFT_REACH = 8;

    /** Every block of layer 1's band in {@code chunk}, by index. */
    private static BlockState[] snapshot(ChunkAccess chunk) {
        BlockState[] out = new BlockState[16 * 16 * LAYER.height()];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = LAYER.minY(); y < LAYER.topExclusive(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    out[i++] = chunk.getBlockState(pos.set(
                            chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z));
                }
            }
        }
        return out;
    }

    /** The positions whose block differs between two snapshots of {@code chunk}. */
    private static List<BlockPos> changed(ChunkAccess chunk, BlockState[] before, BlockState[] after) {
        List<BlockPos> out = new ArrayList<>();
        int i = 0;
        for (int y = LAYER.minY(); y < LAYER.topExclusive(); y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (before[i] != after[i]) {
                        out.add(new BlockPos(
                                chunk.getPos().getMinBlockX() + x,
                                y,
                                chunk.getPos().getMinBlockZ() + z));
                    }
                    i++;
                }
            }
        }
        return out;
    }

    private static Feature feature(ServerLevel level, String placed) {
        return level.registryAccess()
                .lookupOrThrow(Registries.PLACED_FEATURE)
                .getValueOrThrow(ResourceKey.create(
                        Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath("deepcharter", placed)))
                .feature()
                .value();
    }

    /**
     * Every block a shaft changes lies inside its plan's bounds across and under its top (the top may move down to the
     * rock, never sideways), so a shaft never writes past its shell, and never onto the far side of its own chunk,
     * which a write past the chunk's edge lands on.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = FULL, maxTicks = MAX_TICKS)
    public void aShaftWritesOnlyInsideItsBounds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ClaimShaftFeature shaft = (ClaimShaftFeature) feature(level, "claim_shafts");
        RandomSource rows = RandomSource.create(1277);
        int placed = 0;
        List<String> strays = new ArrayList<>();
        for (int i = 0; i < SHAFT_TRIES; i++) {
            ChunkAccess chunk = level.getChunk(FIRST_CHUNK + i, FIRST_CHUNK);
            BlockPos origin = chunk.getPos().getWorldPosition().atY(LAYER.minY() + 30 + rows.nextInt(120));
            BoundingBox bounds = shaft.plan(RandomSource.create(i), origin).bounds();
            BlockState[] before = snapshot(chunk);
            if (!shaft.place(level, level.getChunkSource().getGenerator(), RandomSource.create(i), origin)) {
                continue;
            }
            placed++;
            for (BlockPos at : changed(chunk, before, snapshot(chunk))) {
                boolean inside = at.getX() >= bounds.minX()
                        && at.getX() <= bounds.maxX()
                        && at.getZ() >= bounds.minZ()
                        && at.getZ() <= bounds.maxZ()
                        && at.getY() <= bounds.maxY();
                if (!inside && strays.size() < 8) {
                    strays.add(at.toShortString() + " outside " + bounds);
                }
            }
        }
        LOGGER.info(
                "[claim-features] {} shafts placed of {} tries, {} stray writes listed",
                placed,
                SHAFT_TRIES,
                strays.size());
        expect(helper, placed >= SHAFT_TRIES / 4, "only %s of %s shafts placed", placed, SHAFT_TRIES);
        expect(helper, strays.isEmpty(), "shafts wrote outside their bounds: %s", strays);
        helper.succeed();
    }

    /**
     * A seam turns only the layer's rock into ore, each block on a cave face (open on a side, in its chunk), in the
     * rock it replaced, and of an ore of the zone's table: the zone fill's ores, with the richer ones weighted up
     * against Ironium.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = FULL, maxTicks = MAX_TICKS)
    public void aCaveOreSeamLiesOnACaveFaceInItsZonesOre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CaveOreFeature seams = (CaveOreFeature) feature(level, "claim_cave_ores");
        String[] fills = {"fill_topsoil_claims", "fill_stone_benches", "fill_deep_claim"};
        for (int zone = 0; zone < Zones.COUNT; zone++) {
            ZoneFillFeature fill = (ZoneFillFeature) feature(level, fills[zone]);
            Map<Block, Double> chances = fill.ores().stream()
                    .collect(Collectors.toMap(ZoneFillFeature.Entry::block, ZoneFillFeature.Entry::chance));
            Map<Block, Integer> weights = seams.zones().get(zone).stream()
                    .collect(Collectors.toMap(CaveOreFeature.Weighted::ore, CaveOreFeature.Weighted::weight));
            expect(
                    helper,
                    weights.keySet().equals(chances.keySet()),
                    "zone %s's seams use %s, its fill %s",
                    zone,
                    weights.keySet(),
                    chances.keySet());
            Block iron =
                    BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", "ironium_ore"));
            for (String rich : List.of("silverium_ore", "goldium_ore")) {
                Block ore = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", rich));
                expect(
                        helper,
                        weights.getOrDefault(ore, 0) / (double) weights.getOrDefault(iron, 1)
                                > chances.getOrDefault(ore, 0.0) / chances.getOrDefault(iron, 1.0) * 1.5,
                        "zone %s: %s is not weighted up in the seams against Ironium",
                        zone,
                        rich);
            }
        }
        RandomSource draws = RandomSource.create(1278);
        int seamsPlaced = 0;
        List<String> wrong = new ArrayList<>();
        for (int c = 0; c < SEAM_CHUNKS; c++) {
            ChunkAccess chunk = level.getChunk(FIRST_CHUNK + c, FIRST_CHUNK + 4);
            for (int t = 0; t < SEAM_TRIES_PER_CHUNK; t++) {
                BlockPos origin = chunk.getPos()
                        .getWorldPosition()
                        .offset(draws.nextInt(16), 0, draws.nextInt(16))
                        .atY(LAYER.minY() + 16 + draws.nextInt(140));
                BlockState[] before = snapshot(chunk);
                if (!seams.place(
                        level, level.getChunkSource().getGenerator(), RandomSource.create(c * 100L + t), origin)) {
                    continue;
                }
                seamsPlaced++;
                List<BlockPos> blocks = changed(chunk, before, snapshot(chunk));
                Set<Block> ores = new HashSet<>();
                for (BlockPos at : blocks) {
                    int index = index(chunk, at);
                    BlockState was = before[index];
                    BlockState now = chunk.getBlockState(at);
                    ores.add(now.getBlock());
                    int zone = Zones.index(LAYER.minY(), LAYER.height(), at.getY());
                    boolean inTable = seams.zones().get(zone).stream().anyMatch(w -> w.ore() == now.getBlock());
                    boolean onFace = false;
                    for (Direction side : Direction.values()) {
                        BlockPos next = at.relative(side);
                        onFace |= ChunkPos.containing(next).equals(chunk.getPos())
                                && chunk.getBlockState(next).isAir();
                    }
                    if ((!was.is(LayerBlocks.GROUND)
                                    || !inTable
                                    || !onFace
                                    || !(now.getBlock() instanceof OreBlock ore)
                                    || now != ore.in(was))
                            && wrong.size() < 8) {
                        wrong.add(at.toShortString() + " " + was + " -> " + now + " face " + onFace);
                    }
                }
                if (ores.size() != 1 && wrong.size() < 8) {
                    wrong.add("a seam of " + ores);
                }
            }
        }
        LOGGER.info("[claim-features] {} seams placed", seamsPlaced);
        expect(helper, seamsPlaced >= 20, "only %s seams placed", seamsPlaced);
        expect(helper, wrong.isEmpty(), "seam blocks that are not one zone ore on a cave face: %s", wrong);
        helper.succeed();
    }

    /**
     * The generated claim holds the hooks: seams (three or more blocks of one ore touching, each on a cave face, which
     * the zone fill's one roll a block almost never makes) and salvage crates on cave floors away from the shafts'
     * drifts.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = FULL, maxTicks = MAX_TICKS)
    public void theClaimHasSeamsOnCaveFacesAndCachesOnCaveFloors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Block pail = LayerBlocks.LUNCH_PAIL;
        List<BlockPos> pails = new ArrayList<>();
        List<BlockPos> crates = new ArrayList<>();
        Map<BlockPos, Block> faceOres = new HashMap<>();
        for (int cx = SAMPLE_CHUNK; cx < SAMPLE_CHUNK + SAMPLE_CHUNKS; cx++) {
            for (int cz = SAMPLE_CHUNK; cz < SAMPLE_CHUNK + SAMPLE_CHUNKS; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int y = LAYER.minY(); y < LAYER.topExclusive(); y++) {
                    for (int x = chunk.getPos().getMinBlockX();
                            x <= chunk.getPos().getMaxBlockX();
                            x++) {
                        for (int z = chunk.getPos().getMinBlockZ();
                                z <= chunk.getPos().getMaxBlockZ();
                                z++) {
                            BlockState state = chunk.getBlockState(pos.set(x, y, z));
                            if (state.is(pail)) {
                                pails.add(pos.immutable());
                            } else if (state.is(SurfaceBlocks.SALVAGE_CRATE)
                                    && !chunk.getBlockState(pos.below()).isAir()) {
                                crates.add(pos.immutable());
                            } else if (state.getBlock() instanceof OreBlock && open(level, pos)) {
                                faceOres.put(pos.immutable(), state.getBlock());
                            }
                        }
                    }
                }
            }
        }
        long caches = crates.stream()
                .filter(crate -> pails.stream().noneMatch(p -> p.distManhattan(crate) <= SHAFT_REACH))
                .count();
        int seams = 0;
        for (Map.Entry<BlockPos, Block> ore : faceOres.entrySet()) {
            int neighbours = 0;
            for (Direction side : Direction.values()) {
                if (faceOres.get(ore.getKey().relative(side)) == ore.getValue()) {
                    neighbours++;
                }
            }
            if (neighbours >= 2) {
                seams++;
            }
        }
        LOGGER.info(
                "[claim-features] {} face ores, {} in seams, {} crates of which {} away from shafts",
                faceOres.size(),
                seams,
                crates.size(),
                caches);
        expect(helper, seams >= 20, "only %s face ores sit in a seam of three", seams);
        expect(helper, caches >= 2, "only %s salvage crates on cave floors away from the shafts", caches);
        helper.succeed();
    }

    /**
     * The strata have one truth: the material rule's contacts sit {@link LayerBlocks#CLAIM_CONTACT_UNDER_EDGE} under
     * each of layer 1's zone edges and lay the rocks {@link LayerBlocks#stratum} names either side, and in the
     * generated wall every block of layer rock further than the noise and the surface depth can move a contact is the
     * rock {@code stratum} names.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = FULL, maxTicks = MAX_TICKS)
    public void theStrataRuleAndLayerBlocksAgree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StrataRule rule = (StrataRule) level.registryAccess()
                .lookupOrThrow(Registries.MATERIAL_RULE)
                .getValueOrThrow(ResourceKey.create(
                        Registries.MATERIAL_RULE, Identifier.fromNamespaceAndPath("deepcharter", "claim_strata")));
        LayerChain.Band band = TestLayers.band(level, 1);
        expect(
                helper,
                rule.contacts().size() == Zones.COUNT - 1,
                "the rule has %s contacts",
                rule.contacts().size());
        List<Integer> contacts = new ArrayList<>();
        for (int zone = 0; zone < Zones.COUNT - 1; zone++) {
            StrataRule.Contact contact = rule.contacts().get(zone);
            int y = Zones.span(band.minY(), band.height(), zone).low() - LayerBlocks.CLAIM_CONTACT_UNDER_EDGE;
            contacts.add(y);
            expect(helper, contact.y() == y, "contact %s is at y=%s, LayerBlocks puts it at %s", zone, contact.y(), y);
            expect(helper, contact.upper().is(LayerBlocks.stratum(band, y)), "contact %s's upper rock", zone);
            expect(helper, contact.bed().is(LayerBlocks.stratum(band, y - 1)), "contact %s's bed", zone);
        }
        expect(helper, rule.bottom().is(LayerBlocks.stratum(band, band.minY())), "the rule's bottom rock");
        int reach = 12;
        int checked = 0;
        List<String> wrong = new ArrayList<>();
        for (int cx = SAMPLE_CHUNK; cx < SAMPLE_CHUNK + 3; cx++) {
            ChunkAccess chunk = level.getChunk(cx, SAMPLE_CHUNK);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = band.minY(); y < band.minY() + band.height(); y++) {
                int row = y;
                if (contacts.stream().anyMatch(c -> Math.abs(row - c) <= reach)) {
                    continue;
                }
                for (int x = chunk.getPos().getMinBlockX(); x <= chunk.getPos().getMaxBlockX(); x++) {
                    for (int z = chunk.getPos().getMinBlockZ();
                            z <= chunk.getPos().getMaxBlockZ();
                            z++) {
                        BlockState state = chunk.getBlockState(pos.set(x, y, z));
                        if (state.is(LayerBlocks.GROUND)) {
                            checked++;
                            if (!state.is(LayerBlocks.stratum(band, y)) && wrong.size() < 5) {
                                wrong.add(state + " at " + pos.toShortString());
                            }
                        }
                    }
                }
            }
        }
        expect(helper, checked > 10_000, "only %s rock blocks checked", checked);
        expect(helper, wrong.isEmpty(), "rock that is not LayerBlocks.stratum's: %s", wrong);
        helper.succeed();
    }

    /**
     * The surface rule's fast path keeps a table of the block pairs it has judged, capped at 64 (ADR 0090, amended):
     * the pairs that generating the campaign world writes, layer 1's strata among them, are far fewer, so the table
     * never fills and starts again.
     */
    @GameTest(environment = CampaignEnvironments.C, dimension = FULL, maxTicks = MAX_TICKS)
    public void worldgenJudgesFarFewerPairsThanTheFastPathKeeps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SurfaceWrites.forgetKnown();
        for (int cx = 0; cx < 6; cx++) {
            for (int cz = 0; cz < 6; cz++) {
                level.getChunk(FIRST_CHUNK + 200 + cx, FIRST_CHUNK + 200 + cz);
            }
        }
        int pairs = SurfaceWrites.knownPairs();
        LOGGER.info("[claim-features] generating 36 fresh chunks of the campaign world judged {} block pairs", pairs);
        expect(helper, pairs > 0 && pairs <= 32, "the fast path judged %s pairs, against a table of 64", pairs);
        helper.succeed();
    }

    private static boolean open(ServerLevel level, BlockPos at) {
        for (Direction side : Direction.values()) {
            if (level.getBlockState(at.relative(side)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static int index(ChunkAccess chunk, BlockPos at) {
        int x = at.getX() - chunk.getPos().getMinBlockX();
        int z = at.getZ() - chunk.getPos().getMinBlockZ();
        return ((at.getY() - LAYER.minY()) * 16 + x) * 16 + z;
    }
}
