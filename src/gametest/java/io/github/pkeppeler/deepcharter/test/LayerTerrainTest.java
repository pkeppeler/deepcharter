package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server GameTests for #54: layer terrain, zones and biomes, and hand versus drill on deep rock. #121 adds lava damage to pods. */
public class LayerTerrainTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(LayerTerrainTest.class);

    /** Far layer chunks generate on worker threads, so a pod's own {@code tickCount} is the clock, with a big budget. */
    private static final int MAX_TICKS = 20000;

    /** The zone names of lore canon section 11, top to bottom. */
    private static final String[][] ZONES = {
        {"topsoil_claims", "stone_benches", "deep_claim"},
        {"upper_levels", "shift_change", "prospectors_run"}
    };

    /** Server ticks seen for each pod a lava test marks. Fabric events cannot be unregistered, so this listens once. */
    private static final Map<UUID, Integer> LAVA_TICKS = new ConcurrentHashMap<>();

    private static final int LAVA_TEST_TICKS = 20;
    private static final float EPSILON = 1e-3f;

    static {
        PodEvents.AFTER_TICK.register(pod -> LAVA_TICKS.computeIfPresent(pod.getUUID(), (id, ticks) -> ticks + 1));
    }

    @GameTest
    public void theCrustFloorIsIntact(GameTestHelper helper) {
        int rows = LayerTuning.DEFAULT.crustThickness();
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            int floor = TestLayers.band(level, layer).minY();
            for (int x = 700; x < 748; x++) {
                for (int z = 700; z < 748; z++) {
                    for (int dy = 0; dy < rows; dy++) {
                        expectBlock(helper, level, new BlockPos(x, floor + dy, z), LayerBlocks.BREACH_CRUST);
                    }
                    // Solid rock under the crust, so no cave can open onto it.
                    for (int dy = rows; dy < rows + 4; dy++) {
                        BlockPos pos = new BlockPos(x, floor + dy, z);
                        if (level.getBlockState(pos).isAir()
                                || level.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
                            throw failure(
                                    helper,
                                    "layer_%d %s should be rock under the crust, found %s",
                                    layer,
                                    pos,
                                    level.getBlockState(pos));
                        }
                    }
                }
            }
        }
        helper.succeed();
    }

    /**
     * A layer's band is rock to its ceiling, cut by noise caves: a 64 x 64 sample has caves in it, and the air rows of a column differ
     * from column to column, which flat terrain never does.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void terrainIsNoiseWithCaves(GameTestHelper helper) {
        List<FarChunks.At> sample = new ArrayList<>();
        for (int layer = 1; layer <= 2; layer++) {
            for (int chunkX = 900 >> 4; chunkX <= 963 >> 4; chunkX++) {
                for (int chunkZ = 900 >> 4; chunkZ <= 963 >> 4; chunkZ++) {
                    sample.add(new FarChunks.At(TestLayers.level(helper, layer), chunkX, chunkZ));
                }
            }
        }
        FarChunks.whenBlocksLoaded(helper, sample, () -> terrainIsNoiseWithCavesOnceLoaded(helper));
    }

    private static void terrainIsNoiseWithCavesOnceLoaded(GameTestHelper helper) {
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            LayerChain.Band band = TestLayers.band(level, layer);
            int fewest = Integer.MAX_VALUE;
            int most = 0;
            for (int x = 900; x < 964; x += 4) {
                for (int z = 900; z < 964; z += 4) {
                    int air = 0;
                    for (int y = band.minY() + 12; y < band.maxY() - 8; y++) {
                        if (level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                            air++;
                        }
                    }
                    fewest = Math.min(fewest, air);
                    most = Math.max(most, air);
                }
            }
            if (most == 0) {
                throw failure(helper, "layer %d has no caves in a 64 x 64 sample", layer);
            }
            if (most == fewest) {
                throw failure(helper, "layer %d terrain is flat: every column has %d air rows", layer, most);
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void onlyTheRetainedSpawnNaturally(GameTestHelper helper) {
        FarChunks.whenBlocksLoaded(helper, spawnChunks(helper), () -> onlyTheRetainedSpawnNaturallyOnceLoaded(helper));
    }

    /** Chunk (0, 0) of each layer, where the zone tests read a biome and the mobs the generator lists. */
    private static List<FarChunks.At> spawnChunks(GameTestHelper helper) {
        return List.of(
                new FarChunks.At(TestLayers.level(helper, 1), 0, 0),
                new FarChunks.At(TestLayers.level(helper, 2), 0, 0));
    }

    private static void onlyTheRetainedSpawnNaturallyOnceLoaded(GameTestHelper helper) {
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            for (int zone = 0; zone < 3; zone++) {
                BlockPos pos = new BlockPos(0, zoneMiddle(level, layer, zone), 0);
                for (MobCategory category : MobCategory.values()) {
                    var mobs = level.getChunkSource()
                            .getGenerator()
                            .getMobsAt(level, level.structureManager(), category, pos);
                    // #449: the Retained are the one thing the biomes of layers 1 and 2 list, as a rare monster.
                    boolean onlyRetained = category == MobCategory.MONSTER
                            && mobs.unwrap().stream()
                                    .allMatch(entry -> entry.value().type() == CreatureRegistry.LAMPLESS_FIGURE);
                    if (!mobs.isEmpty() && !onlyRetained) {
                        throw failure(
                                helper, "layer_%d zone %d spawns %s mobs: %s", layer, zone, category, mobs.unwrap());
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void zoneBoundsAreRight(GameTestHelper helper) {
        // Zone 0 is the top third. Layer 1 is 192 tall: thirds at 64 and 128. Layer 2 is 256 tall: 85.33 and 170.67.
        // The Ys are local to the
        // layer's band, counted from its lowest block.
        expectZones(helper, 1, new int[] {0, 63, 64, 127, 128, 191}, new int[] {2, 2, 1, 1, 0, 0});
        expectZones(helper, 2, new int[] {0, 85, 86, 170, 171, 255}, new int[] {2, 2, 1, 1, 0, 0});
        if (Zones.of(helper.getLevel().getServer().overworld(), 63).isPresent()) {
            throw failure(helper, "the surface has a zone");
        }
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            LayerChain.Band levelBand = TestLayers.band(level, layer);
            int inLayer = layer;
            for (int bad :
                    new int[] {levelBand.minY() - 1, levelBand.maxY() + 1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                // Outside a band is the surface, unbuilt depth or the other layer, which has a zone of its own.
                if (Zones.of(level, bad).filter(zone -> zone.layer() == inLayer).isPresent()) {
                    throw failure(
                            helper, "layer %d y=%d is outside the layer and should have no zone of it", layer, bad);
                }
            }
        }
        helper.succeed();
    }

    /** A layer past the named ones has no zone, and never throws: callers pass any player Y every tick. */
    @GameTest
    public void anUnnamedLayerHasNoZone(GameTestHelper helper) {
        int unnamed = CampaignBands.lastLayer() + 1;
        if (Zones.of(unnamed, 0, 192, 100).isPresent()
                || Zones.of(0, 0, 192, 100).isPresent()) {
            throw failure(helper, "a layer without names has a zone");
        }
        if (Zones.of(1, 0, 192, 100).isEmpty()) {
            throw failure(helper, "layer 1 has no zone at y=100");
        }
        helper.succeed();
    }

    /** The names must cover every layer in the chain, as the campaign world lays it out; this is the start-up check, run here as well. */
    @GameTest
    public void zoneNamesCoverEveryLayerInTheChain(GameTestHelper helper) {
        Zones.requireNamesFor(CampaignBands.lastLayer());
        try {
            Zones.requireNamesFor(CampaignBands.lastLayer() + 1);
        } catch (IllegalStateException expected) {
            helper.succeed();
            return;
        }
        throw failure(helper, "a chain longer than the named layers passed the check");
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void eachZoneHasItsOwnBiomeNamedInTheLore(GameTestHelper helper) {
        FarChunks.whenBlocksLoaded(helper, spawnChunks(helper), () -> eachZoneHasItsOwnBiomeOnceLoaded(helper));
    }

    private static void eachZoneHasItsOwnBiomeOnceLoaded(GameTestHelper helper) {
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            for (int zone = 0; zone < 3; zone++) {
                Identifier expected = Identifier.fromNamespaceAndPath("deepcharter", ZONES[layer - 1][zone]);
                Zones.Zone found =
                        Zones.of(level, zoneMiddle(level, layer, zone)).orElseThrow();
                if (!found.id().equals(expected) || found.layer() != layer || found.index() != zone) {
                    throw failure(helper, "layer_%d zone %d is %s, expected %s", layer, zone, found, expected);
                }
                BlockPos pos = new BlockPos(0, zoneMiddle(level, layer, zone), 0);
                Identifier biome = level.getBiome(pos).unwrapKey().orElseThrow().identifier();
                if (!biome.equals(expected)) {
                    throw failure(helper, "layer_%d zone %d has biome %s, expected %s", layer, zone, biome, expected);
                }
            }
        }
        helper.succeed();
    }

    /**
     * Below layer 1 rock is pod-only (SPEC section 9, ADR 0069): an empty hand cannot break it; layer 1's is ordinary.
     * {@link HandBreakTest} covers the tools that can.
     */
    @GameTest
    public void bareHandsCannotBreakDeepRock(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "terrain-hands");
        ServerLevel one = TestLayers.level(helper, 1);
        ServerLevel two = TestLayers.level(helper, 2);
        BlockPos pos = TestLayers.pos(2, 1700, 80, 1700);
        BlockPos pos1 = TestLayers.pos(1, 1700, 80, 1700);
        mock.teleportTo(two, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
        mock.player().setGameMode(GameType.SURVIVAL);
        two.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        two.setBlock(pos.east(), Blocks.DIRT.defaultBlockState(), 3);
        if (mock.player().gameMode.destroyBlock(pos) || !two.getBlockState(pos).is(Blocks.STONE)) {
            throw failure(helper, "a bare-handed survival player broke rock in layer_2");
        }
        if (!mock.player().gameMode.destroyBlock(pos.east())) {
            throw failure(helper, "the guard stopped dirt, which is not rock");
        }
        two.setBlock(pos.west(), Blocks.IRON_ORE.defaultBlockState(), 3);
        if (mock.player().gameMode.destroyBlock(pos.west())
                || !two.getBlockState(pos.west()).is(Blocks.IRON_ORE)) {
            throw failure(helper, "a bare-handed survival player broke iron ore in layer_2");
        }
        mock.player().setGameMode(GameType.ADVENTURE);
        if (mock.player().gameMode.destroyBlock(pos) || !two.getBlockState(pos).is(Blocks.STONE)) {
            throw failure(helper, "a bare-handed adventure player broke rock in layer_2");
        }
        mock.player().setGameMode(GameType.CREATIVE);
        if (!mock.player().gameMode.destroyBlock(pos)) {
            throw failure(helper, "a creative player could not break rock");
        }
        mock.teleportTo(one, Vec3.atBottomCenterOf(pos1.above(4)), 0, 0);
        mock.player().setGameMode(GameType.SURVIVAL);
        one.setBlock(pos1, Blocks.STONE.defaultBlockState(), 3);
        if (!mock.player().gameMode.destroyBlock(pos1)) {
            throw failure(helper, "a survival player could not break rock by hand in layer 1");
        }
        helper.succeed();
    }

    /** The attack callback runs on the client too, so a swing with no tool starts no crack animation. */
    @GameTest
    public void startingToBreakDeepRockWithABareHandIsRefused(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "terrain-attack");
        ServerLevel one = TestLayers.level(helper, 1);
        ServerLevel two = TestLayers.level(helper, 2);
        BlockPos pos = TestLayers.pos(2, 1750, 80, 1750);
        BlockPos pos1 = TestLayers.pos(1, 1750, 80, 1750);
        two.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        one.setBlock(pos1, Blocks.STONE.defaultBlockState(), 3);
        two.setBlock(pos.east(), Blocks.DIRT.defaultBlockState(), 3);
        mock.teleportTo(two, Vec3.atBottomCenterOf(pos.above(4)), 0, 0);
        mock.player().setGameMode(GameType.SURVIVAL);
        expectAttack(helper, mock, two, pos, InteractionResult.FAIL);
        expectAttack(helper, mock, two, pos.east(), InteractionResult.PASS);
        mock.player().setGameMode(GameType.CREATIVE);
        expectAttack(helper, mock, two, pos, InteractionResult.PASS);
        mock.teleportTo(one, Vec3.atBottomCenterOf(pos1.above(4)), 0, 0);
        mock.player().setGameMode(GameType.SURVIVAL);
        expectAttack(helper, mock, one, pos1, InteractionResult.PASS);
        helper.succeed();
    }

    private static void expectAttack(
            GameTestHelper helper, MockPlayer mock, ServerLevel level, BlockPos pos, InteractionResult expected) {
        InteractionResult result = AttackBlockCallback.EVENT
                .invoker()
                .interact(mock.player(), level, InteractionHand.MAIN_HAND, pos, Direction.UP);
        if (result != expected) {
            throw failure(
                    helper,
                    "attacking %s in %s gave %s, expected %s",
                    pos,
                    level.dimension().identifier(),
                    result,
                    expected);
        }
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPodDrillBreaksDeepRock(GameTestHelper helper) {
        ServerLevel two = TestLayers.level(helper, 2);
        int x = 1800;
        int z = 1800;
        int floor = TestLayers.worldY(2, 80);
        RoomCarver.carve(two, x - 4, x + 3, floor - 6, floor - 1, z - 4, z + 3, Blocks.STONE);
        RoomCarver.carve(two, x - 4, x + 3, floor, floor + 10, z - 4, z + 3, Blocks.AIR);
        MockPlayer pilot = MockPlayers.join(helper, "terrain-drill");
        Vec3 at = new Vec3(x, floor, z);
        pilot.teleportTo(two, at, 0f, 0f);
        PodEntity pod = TestEntities.create(PodRegistry.POD, two);
        pod.setPos(at);
        two.addFreshEntity(pod);
        if (!pilot.player().startRiding(pod)) {
            throw failure(helper, "the pilot could not mount the pod");
        }
        pilot.setInput(SPRINT);
        TestTicks.each(helper, () -> {
            if (two.getBlockState(new BlockPos(x - 1, floor - 1, z - 1)).isAir()) {
                pilot.releaseInput();
                pod.discard();
                helper.succeed();
            }
        });
    }

    /** The generator's own copies of the layers' ranges must equal the level's and the band table's, or zones and terrain drift apart. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void generatorMatchesTheBands(GameTestHelper helper) {
        FarChunks.whenBlocksLoaded(
                helper, List.of(new FarChunks.At(TestLayers.level(helper, 1), 0, 0)), () -> generatorMatches(helper));
    }

    private static void generatorMatches(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            throw failure(helper, "the campaign level is not generated by noise");
        }
        var noise = generator.generatorSettings().value().noiseSettings();
        // level-height: the campaign generator's noise spans the whole level, not one layer
        int levelMin = level.getMinY();
        // level-height: as above
        int levelHeight = level.getHeight();
        if (noise.minY() != levelMin || noise.height() != levelHeight) {
            throw failure(
                    helper,
                    "the noise range %d+%d differs from the level's %d+%d",
                    noise.minY(),
                    noise.height(),
                    levelMin,
                    levelHeight);
        }
        if (!(generator.getBiomeSource() instanceof BandBiomeSource)) {
            throw failure(helper, "the campaign level is not given its biomes by band");
        }
        // The stored cell of a chunk, never level.getBiome, which blurs a band's edge.
        var chunk = Objects.requireNonNull(level.getChunkSource().getChunkNow(0, 0), "chunk (0, 0) is loaded");
        for (int layer = 1; layer <= 2; layer++) {
            LayerChain.Band levelBand = TestLayers.band(level, layer);
            for (int quartY = QuartPos.fromBlock(levelBand.minY());
                    quartY <= QuartPos.fromBlock(levelBand.maxY());
                    quartY++) {
                int y = Math.clamp(QuartPos.toBlock(quartY) + 2, levelBand.minY(), levelBand.maxY());
                Identifier drawn = chunk.getNoiseBiome(0, quartY, 0)
                        .unwrapKey()
                        .orElseThrow()
                        .identifier();
                Identifier expected = Zones.of(level, y).orElseThrow().id();
                if (!drawn.equals(expected)) {
                    throw failure(
                            helper,
                            "layer %d: the generated chunk holds the biome %s at y=%d, the zone there is %s",
                            layer,
                            drawn,
                            y,
                            expected);
                }
            }
        }
        helper.succeed();
    }

    /** Logs how long fresh far chunks take, which is the cost the noise generators add. */
    @GameTest
    public void chunkGenerationTimeIsLogged(GameTestHelper helper) {
        int side = 4;
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = TestLayers.level(helper, layer);
            int base = 100_000 + layer * 1000;
            long start = System.nanoTime();
            for (int cx = base; cx < base + side; cx++) {
                for (int cz = base; cz < base + side; cz++) {
                    // sync-load: the test times what the generation of a fresh chunk costs, so it must run on this
                    // thread
                    level.getChunk(cx, cz, ChunkStatus.FULL);
                }
            }
            double millis = (System.nanoTime() - start) / 1e6;
            double perChunk = millis / (side * side);
            LOGGER.info(
                    "Chunk generation time, layer_{}: {} chunks in {} ms ({} ms per chunk)",
                    layer,
                    side * side,
                    Math.round(millis),
                    String.format("%.1f", perChunk));
            if (perChunk > 5000) {
                throw failure(helper, "layer_%d takes %.0f ms per chunk to generate", layer, perChunk);
            }
        }
        helper.succeed();
    }

    private static void expectZones(GameTestHelper helper, int layer, int[] localYs, int[] expected) {
        ServerLevel level = TestLayers.level(helper, layer);
        for (int i = 0; i < localYs.length; i++) {
            int y = TestLayers.worldY(layer, localYs[i]);
            Zones.Zone zone = Zones.of(level, y).orElseThrow();
            if (zone.index() != expected[i]) {
                throw failure(
                        helper,
                        "layer %d at local y=%d (y=%d) is in zone %d, expected %d",
                        layer,
                        localYs[i],
                        y,
                        zone.index(),
                        expected[i]);
            }
        }
    }

    /** The middle Y of a zone (0 is the top third), away from the edges, where the 4-block biome cells cannot disagree with it. */
    private static int zoneMiddle(ServerLevel level, int layer, int zone) {
        LayerChain.Band band = TestLayers.band(level, layer);
        return band.minY() + band.height() * (5 - 2 * zone) / 6;
    }

    /** Lays a stone floor and puts a pod on it at {@code podX} (relative), marked so {@link #LAVA_TICKS} counts its ticks. */
    private static PodEntity lavaPod(GameTestHelper helper, double podX) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(podX, 2, 3.5));
        LAVA_TICKS.put(pod.getUUID(), 0);
        return pod;
    }

    /** Runs {@code check} after {@link #LAVA_TEST_TICKS} ticks, then removes the pod and lava; the test passes if {@code check} does not throw. */
    private static void afterLavaTicks(GameTestHelper helper, PodEntity pod, BlockPos lava, Runnable check) {
        helper.runAfterDelay(LAVA_TEST_TICKS, () -> {
            try {
                check.run();
            } finally {
                LAVA_TICKS.remove(pod.getUUID());
                pod.discard();
                // room-carver: removes a block this test placed itself (its lava) in the overworld test structure, not
                // layer rock
                helper.setBlock(lava, Blocks.AIR);
            }
            helper.succeed();
        });
    }

    private static void expectLavaHull(GameTestHelper helper, PodEntity pod, float perTick) {
        int ticks = Objects.requireNonNull(LAVA_TICKS.get(pod.getUUID()), "LAVA_TICKS.get(pod.getUUID()) is absent");
        float expected = pod.maxHull() - ticks * perTick;
        if (ticks < LAVA_TEST_TICKS || Math.abs(pod.hull() - expected) > EPSILON) {
            throw failure(helper, "after %d ticks the hull should be %s, found %s", ticks, expected, pod.hull());
        }
    }

    @GameTest(maxTicks = LAVA_TEST_TICKS + 20)
    public void lavaThePodIsInLowersTheHullAtTheTunedRate(GameTestHelper helper) {
        PodEntity pod = lavaPod(helper, 3.5);
        BlockPos lava = new BlockPos(3, 2, 3);
        helper.setBlock(lava, Blocks.LAVA);
        afterLavaTicks(
                helper, pod, lava, () -> expectLavaHull(helper, pod, LayerTuning.DEFAULT.lavaHullPerSecond() / 20f));
    }

    @GameTest(maxTicks = LAVA_TEST_TICKS + 20)
    public void lavaOnlyTouchingThePodLowersTheHullToo(GameTestHelper helper) {
        // The pod is 1.9 wide, so with its centre at 4.05 its east face is at x 5.0, where the lava block starts.
        PodEntity pod = lavaPod(helper, 4.05);
        BlockPos lava = new BlockPos(5, 2, 3);
        helper.setBlock(lava, Blocks.LAVA);
        afterLavaTicks(
                helper, pod, lava, () -> expectLavaHull(helper, pod, LayerTuning.DEFAULT.lavaHullPerSecond() / 20f));
    }

    @GameTest(maxTicks = LAVA_TEST_TICKS + 20)
    public void aPodClearOfLavaTakesNoDamage(GameTestHelper helper) {
        // The pod's east face is at x 4.45 and the lava starts at x 6.
        PodEntity pod = lavaPod(helper, 3.5);
        BlockPos lava = new BlockPos(6, 2, 3);
        helper.setBlock(lava, Blocks.LAVA);
        afterLavaTicks(helper, pod, lava, () -> expectLavaHull(helper, pod, 0f));
    }

    @GameTest(maxTicks = LAVA_TEST_TICKS + 20)
    public void lavaCanTakeTheLastHullAndWreckThePod(GameTestHelper helper) {
        PodEntity pod = lavaPod(helper, 3.5);
        pod.setHull(1f);
        BlockPos lava = new BlockPos(3, 2, 3);
        helper.setBlock(lava, Blocks.LAVA);
        afterLavaTicks(helper, pod, lava, () -> {
            if (!Wrecks.isWreck(pod) || pod.hull() != 0f) {
                throw failure(
                        helper, "the lava should have taken the last hull and wrecked the pod, hull is %s", pod.hull());
            }
        });
    }

    @GameTest(maxTicks = LAVA_TEST_TICKS + 20)
    public void aRadiatorCutsTheLavaDamage(GameTestHelper helper) {
        PodEntity pod = lavaPod(helper, 3.5);
        CharterId owner = CharterId.random();
        PodComponents.register(pod, owner);
        PodComponents.install(
                pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.RADIATOR, 2, owner));
        BlockPos lava = new BlockPos(3, 2, 3);
        helper.setBlock(lava, Blocks.LAVA);
        // A tier 2 radiator takes 0.75 of the stock damage (UpgradeTuning).
        afterLavaTicks(
                helper,
                pod,
                lava,
                () -> expectLavaHull(helper, pod, LayerTuning.DEFAULT.lavaHullPerSecond() / 20f * 0.75f));
    }

    @GameTest(maxTicks = LAVA_TEST_TICKS + 20)
    public void aRadiatorAboveTheChassisTierCapWorksAtTheCap(GameTestHelper helper) {
        PodEntity pod = lavaPod(helper, 3.5);
        CharterId owner = CharterId.random();
        PodComponents.register(pod, owner);
        PodComponents.install(
                pod, ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.RADIATOR, 3, owner));
        BlockPos lava = new BlockPos(3, 2, 3);
        helper.setBlock(lava, Blocks.LAVA);
        // The Mole caps components at tier 2, so a tier 3 radiator still takes 0.75.
        afterLavaTicks(
                helper,
                pod,
                lava,
                () -> expectLavaHull(helper, pod, LayerTuning.DEFAULT.lavaHullPerSecond() / 20f * 0.75f));
    }

    private static void expectBlock(GameTestHelper helper, ServerLevel level, BlockPos pos, Block block) {
        if (!level.getBlockState(pos).is(block)) {
            throw failure(
                    helper,
                    "%s %s should be %s, found %s",
                    level.dimension().identifier(),
                    pos,
                    block,
                    level.getBlockState(pos));
        }
    }
}
