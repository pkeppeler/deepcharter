package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Proves that a change to world generation leaves the world as it was (#743's cost cuts). Without {@value #OUTPUT_ENV} it does nothing
 * and passes. With {@code DEEPCHARTER_WORLD_HASH=<output file> tools/gametest.sh 'campaign_world_hash_test*'} it generates two 6 x 6
 * patches of chunks in each world where the zone fill runs, and writes one line per chunk: {@code <world> <cx> <cz> <blocks> <biomes>}.
 * The block hash covers every block state by its string form (name and properties) and the biome hash every 4-block biome cell by its
 * registry key, never by a registry id, which shifts when a registry gains an entry. Run it on the tree before a change and on the
 * tree after, and {@code diff} the two files: no difference is the proof.
 *
 * <p>The server GameTest world has the fixed seed 0, and a level's seed is set when the server starts, so a second seed is a second run
 * of the server, not a second case here. The second patch, far from the first, is the cheap second sample of the same noise.
 */
public class CampaignWorldHashTest {
    static final String OUTPUT_ENV = "DEEPCHARTER_WORLD_HASH";
    private static final String OUTPUT = System.getenv(OUTPUT_ENV);

    /** The worlds whose generation runs the zone fill: the campaign, a layer, and the layer-rock baseline. */
    private static final List<String> WORLDS = List.of("campaign_sample", "layer_1", "tall_layer");
    /** The low corner chunk of each 6 x 6 patch. */
    private static final int[] PATCH_CORNERS = {700, -402};

    private static final int PATCH = 6;
    private static final long FNV_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    @GameTest(maxTicks = 200_000)
    public void aWorldGenerationChangeLeavesTheWorldIdentical(GameTestHelper helper) throws IOException {
        if (OUTPUT == null) {
            helper.succeed();
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("seed " + helper.getLevel().getSeed());
        // One state's hash is of its string, computed once: the same state hashes alike in every chunk and in
        // every tree.
        Map<BlockState, Long> stateHashes = new HashMap<>();
        for (String world : WORLDS) {
            ServerLevel level = ClientLight.serverLevel(
                    helper.getLevel().getServer(),
                    ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("deepcharter", world)));
            for (int corner : PATCH_CORNERS) {
                for (int cx = corner; cx < corner + PATCH; cx++) {
                    for (int cz = corner; cz < corner + PATCH; cz++) {
                        // Generated and read in this one call, on this tick, so nothing needs forcing or waiting on.
                        LevelChunk chunk = level.getChunk(cx, cz);
                        lines.add(world + " " + cx + " " + cz + " " + Long.toHexString(blocks(chunk, stateHashes)) + " "
                                + Long.toHexString(biomes(chunk)));
                    }
                }
            }
        }
        Files.writeString(Path.of(OUTPUT), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        if (lines.size() != 1 + WORLDS.size() * PATCH_CORNERS.length * PATCH * PATCH) {
            throw helper.assertionException(Component.literal("wrote " + lines.size() + " lines"));
        }
        helper.succeed();
    }

    private static long blocks(LevelChunk chunk, Map<BlockState, Long> stateHashes) {
        long hash = FNV_BASIS;
        for (LevelChunkSection section : chunk.getSections()) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        hash = mix(
                                hash,
                                stateHashes.computeIfAbsent(
                                        section.getBlockState(x, y, z), CampaignWorldHashTest::text));
                    }
                }
            }
        }
        return hash;
    }

    private static long biomes(LevelChunk chunk) {
        long hash = FNV_BASIS;
        Map<Holder<Biome>, Long> keyHashes = new HashMap<>();
        for (LevelChunkSection section : chunk.getSections()) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        hash = mix(
                                hash,
                                keyHashes.computeIfAbsent(section.getNoiseBiome(x, y, z), CampaignWorldHashTest::key));
                    }
                }
            }
        }
        return hash;
    }

    private static long text(BlockState state) {
        return fnv(state.toString());
    }

    private static long key(Holder<Biome> biome) {
        return fnv(biome.unwrapKey().orElseThrow().identifier().toString());
    }

    private static long fnv(String text) {
        long hash = FNV_BASIS;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (b & 0xff)) * FNV_PRIME;
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * FNV_PRIME;
    }
}
