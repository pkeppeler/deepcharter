package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.surface.SurfaceWrites;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;

/**
 * The surface rule's fast column write (#1160, ADR 0090) changes nothing the slow one would: the guard says no to every pair
 * whose bookkeeping differs, and a run of writes through {@code SurfaceWrites.set} leaves a chunk equal, in blocks, heightmaps and
 * section flags, to the same run through {@code ProtoChunk.setBlockState}.
 */
public class SurfaceWritesTest {
    private static final BlockState GREEN = LayerBlocks.GREEN_SHALE.defaultBlockState();
    private static final BlockState PACKED = LayerBlocks.PACKED_SHALE.defaultBlockState();
    private static final List<BlockState> PALETTE = List.of(
            GREEN,
            PACKED,
            PACKED,
            GREEN,
            Blocks.STONE.defaultBlockState(),
            Blocks.AIR.defaultBlockState(),
            Blocks.WATER.defaultBlockState(),
            Blocks.LAVA.defaultBlockState(),
            Blocks.GLOWSTONE.defaultBlockState(),
            Blocks.OAK_LEAVES.defaultBlockState(),
            Blocks.GLASS.defaultBlockState(),
            Blocks.FURNACE.defaultBlockState(),
            Blocks.TORCH.defaultBlockState(),
            Blocks.SOUL_SAND.defaultBlockState(),
            Blocks.SLIME_BLOCK.defaultBlockState());

    /** Rock for rock qualifies; anything the slow path would read differently does not. */
    @GameTest
    public void onlyAnEquivalentSwapIsNeutral(GameTestHelper helper) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        expect(helper, SurfaceWrites.neutral(GREEN, PACKED), "shale over shale is neutral");
        expect(helper, SurfaceWrites.neutral(stone, GREEN), "stone over a rock is neutral");
        expect(helper, !SurfaceWrites.neutral(Blocks.AIR.defaultBlockState(), stone), "air to stone is not");
        expect(helper, !SurfaceWrites.neutral(stone, Blocks.AIR.defaultBlockState()), "stone to air is not");
        expect(helper, !SurfaceWrites.neutral(stone, Blocks.LAVA.defaultBlockState()), "stone to lava is not");
        expect(helper, !SurfaceWrites.neutral(Blocks.WATER.defaultBlockState(), stone), "water over stone is not");
        expect(helper, !SurfaceWrites.neutral(stone, Blocks.GLOWSTONE.defaultBlockState()), "a light source is not");
        expect(helper, !SurfaceWrites.neutral(stone, Blocks.FURNACE.defaultBlockState()), "a block entity is not");
        expect(helper, !SurfaceWrites.neutral(Blocks.FURNACE.defaultBlockState(), stone), "over a block entity is not");
        expect(
                helper,
                !SurfaceWrites.neutral(stone, Blocks.OAK_LEAVES.defaultBlockState()),
                "leaves differ in a heightmap");
        expect(
                helper,
                !SurfaceWrites.neutral(stone, Blocks.GLASS.defaultBlockState()),
                "glass differs in light and heightmap");
        // Equal light properties, so only the heightmap branch can refuse this pair: grass is no motion block,
        // glass is.
        BlockState grass = Blocks.SHORT_GRASS.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        expect(helper, !LightEngine.hasDifferentLightProperties(grass, glass), "grass and glass light alike");
        expect(helper, !SurfaceWrites.neutral(grass, glass), "grass to glass differs only in a heightmap");
        helper.succeed();
    }

    /** The same random writes through the fast and the slow path leave two chunks the same. */
    @GameTest
    public void theFastWriteLeavesTheChunkAsTheSlowOneDoes(GameTestHelper helper) {
        // The surface step runs on a chunk whose status is the one before TERRAIN (BIOMES), which keeps no heightmap;
        // TERRAIN is the first status that keeps them, so a heightmap that is kept is compared too.
        boolean was = SurfaceWrites.setEnabled(true);
        try {
            compare(helper, ChunkStatus.BIOMES, 1_000_002);
            compare(helper, ChunkStatus.TERRAIN, 1_000_006);
        } finally {
            SurfaceWrites.setEnabled(was);
        }
        helper.succeed();
    }

    /** A chunk that is not a plain proto chunk, or has reached the light status, keeps vanilla's write. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aLiveOrLitChunkIsWrittenTheVanillaWay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState sandstone = Blocks.SANDSTONE.defaultBlockState();
        expect(helper, SurfaceWrites.neutral(sandstone, GREEN), "the pair is one the fast path would take");
        // A chunk far from every test, wrapped as the generator wraps a finished one.
        FarChunks.whenBlocksLoaded(helper, List.of(new FarChunks.At(level, 1_000_100, 1_000_100)), () -> {
            LevelChunk live = Objects.requireNonNull(
                    level.getChunkSource().getChunkNow(1_000_100, 1_000_100), "whenBlocksLoaded loaded the chunk");
            ImposterProtoChunk imposter = new ImposterProtoChunk(live, true);
            BlockPos at = at(live, 3, level.getMinY() + 10, 3); // level-height: a far chunk of the test level
            expect(helper, live.getBlockState(at) == sandstone, "the far chunk holds sandstone at %s", at);
            expect(
                    helper,
                    SurfaceWrites.neutralWas(imposter, at, GREEN) == null,
                    "an imposter chunk takes the fast path");
            imposter.setBlockState(at, GREEN, 3);
            expect(helper, live.getBlockState(at) == GREEN, "the vanilla write did not reach the live chunk");
            live.setBlockState(at, sandstone, 3);

            ProtoChunk lit = chunk(level, 1_000_110, ChunkStatus.BIOMES);
            BlockPos inLit = at(lit, 3, level.getMinY() + 10, 3); // level-height: a hand-built chunk
            lit.setBlockState(inLit, GREEN, 3);
            expect(
                    helper,
                    SurfaceWrites.neutralWas(lit, inLit, PACKED) != null,
                    "a proto chunk before the light status does not");
            lit.setPersistedStatus(ChunkStatus.INITIALIZE_LIGHT);
            expect(
                    helper,
                    SurfaceWrites.neutralWas(lit, inLit, PACKED) == null,
                    "a chunk at the light status takes the fast path");
            helper.succeed();
        });
    }

    /**
     * Generates one chunk of layer 1's window through the normal worldgen with the fast path on, and the same chunk of the campaign
     * world (the window's rows are its, ADR 0086) with it off: the mixin must take the path (a count of that chunk's writes), and the band's blocks
     * must be those the vanilla path writes. The window chunk's four finished heightmaps must also equal what its blocks say.
     */
    @GameTest(maxTicks = 2400)
    public void aGeneratedLayerOneChunkTakesTheFastPathAndMatchesVanilla(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel window = layer(server, "campaign_layer_1");
        ServerLevel campaign = layer(server, "campaign_sample");
        // The campaign world's chunk first, with the path off, then the window's with it on, watching only that
        // position: the count is of one chunk position, so another test's worldgen cannot move it. The toggle is
        // flipped only while this test waits on the one chunk.
        LevelChunk off;
        boolean was = SurfaceWrites.setEnabled(false);
        try {
            // sync-load: the fast path is off only while this chunk generates, which must end in this tick
            off = campaign.getChunk(1_000_200, 1_000_200);
        } finally {
            SurfaceWrites.setEnabled(was);
        }
        LevelChunk on;
        SurfaceWrites.watch(new ChunkPos(1_000_200, 1_000_200));
        try {
            // sync-load: the watched count is of this one chunk, so its generation must end while the watch is set
            on = window.getChunk(1_000_200, 1_000_200);
            long taken = SurfaceWrites.watchedWrites();
            expect(
                    helper,
                    taken > 20_000,
                    "layer 1's chunk took the fast path %s times: the mixin is not on its writes",
                    taken);
        } finally {
            SurfaceWrites.watch(null);
        }
        CampaignBands.Band band = CampaignBands.LAYER_1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = band.minY(); y < band.topExclusive(); y++) {
                    BlockPos at = at(on, x, y, z);
                    expect(
                            helper,
                            on.getBlockState(at) == off.getBlockState(at),
                            "%s: %s with the path, %s without",
                            at,
                            on.getBlockState(at),
                            off.getBlockState(at));
                }
                for (Heightmap.Types type : ChunkStatus.FINAL_HEIGHTMAPS) {
                    int top = on.getMinY() - 1; // level-height: reads the window's own rows to recompute a heightmap
                    for (int y = on.getMaxY(); y >= on.getMinY(); y--) { // level-height: as above
                        if (type.isOpaque().test(on.getBlockState(at(on, x, y, z)))) {
                            top = y;
                            break;
                        }
                    }
                    expect(
                            helper,
                            on.getHeight(type, x, z) == top,
                            "%s at %s %s: stored %s, blocks say %s",
                            type,
                            x,
                            z,
                            on.getHeight(type, x, z),
                            top);
                }
            }
        }
        helper.succeed();
    }

    private static ServerLevel layer(MinecraftServer server, String path) {
        return ClientLight.serverLevel(
                server, ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", path)));
    }

    private static void compare(GameTestHelper helper, ChunkStatus status, int chunkX) {
        ServerLevel level = helper.getLevel();
        ProtoChunk fast = chunk(level, chunkX, status);
        ProtoChunk slow = chunk(level, chunkX + 2, status);
        int minY = level.getMinY(); // level-height: a hand-built chunk of the test level, no layer
        // The same base in both, as the noise fill leaves it: rock below, air above.
        for (ProtoChunk chunk : List.of(fast, slow)) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y < minY + 70; y++) {
                        chunk.setBlockState(at(chunk, x, y, z), GREEN, 3);
                    }
                }
            }
            Heightmap.primeHeightmaps(chunk, status.heightmapsAfter());
        }
        RandomSource random = RandomSource.create(1160);
        int neutral = 0;
        for (int i = 0; i < 20_000; i++) {
            int x = random.nextInt(16);
            int y = minY + random.nextInt(80);
            int z = random.nextInt(16);
            BlockState state = PALETTE.get(random.nextInt(PALETTE.size()));
            BlockPos at = at(fast, x, y, z);
            if (SurfaceWrites.neutral(fast.getBlockState(at), state)) {
                neutral++;
            }
            BlockState seen = SurfaceWrites.set(fast, at, state);
            BlockState wasSlow = slow.setBlockState(at(slow, x, y, z), state, 3);
            expect(helper, seen == wasSlow, "write %s returned %s on the fast path, %s on the slow", i, seen, wasSlow);
        }
        expect(
                helper,
                neutral > 2_000 && neutral < 18_000,
                "%s of 20000 writes were neutral: the run tests one path",
                neutral);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < minY + 90; y++) {
                    BlockState a = fast.getBlockState(at(fast, x, y, z));
                    BlockState b = slow.getBlockState(at(slow, x, y, z));
                    expect(helper, a == b, "block %s %s %s: %s fast, %s slow", x, y, z, a, b);
                }
                for (Heightmap.Types type : ChunkStatus.FINAL_HEIGHTMAPS) {
                    expect(
                            helper,
                            fast.getHeight(type, x, z) == slow.getHeight(type, x, z),
                            "%s at %s %s: %s fast, %s slow",
                            type,
                            x,
                            z,
                            fast.getHeight(type, x, z),
                            slow.getHeight(type, x, z));
                }
            }
        }
        LevelChunkSection[] a = fast.getSections();
        LevelChunkSection[] b = slow.getSections();
        for (int i = 0; i < a.length; i++) {
            expect(
                    helper,
                    a[i].hasOnlyAir() == b[i].hasOnlyAir()
                            && a[i].isRandomlyTickingBlocks() == b[i].isRandomlyTickingBlocks()
                            && a[i].isRandomlyTickingFluids() == b[i].isRandomlyTickingFluids(),
                    "section %s flags differ",
                    i);
        }
        expect(
                helper,
                Arrays.equals(fast.getPostProcessing(), slow.getPostProcessing()),
                "post-processing marks differ");
    }

    private static BlockPos at(ChunkAccess chunk, int x, int y, int z) {
        return new BlockPos(chunk.getPos().getBlockX(x), y, chunk.getPos().getBlockZ(z));
    }

    private static ProtoChunk chunk(ServerLevel level, int chunkX, ChunkStatus status) {
        ProtoChunk chunk = new ProtoChunk(
                new ChunkPos(chunkX, chunkX), UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
        chunk.setPersistedStatus(status);
        return chunk;
    }
}
