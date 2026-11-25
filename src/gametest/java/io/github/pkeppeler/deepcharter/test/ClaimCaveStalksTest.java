package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 1's cave stalks ({@code fungal_stalks_cave}, Topsoil Claims) write only inside the chunk they are placed for (ADR
 * 0088, #1348), read from the blocks they write and not from where they plan to. The placed feature runs, every
 * placement step and the stalk itself, on a stand-in level: floors of stone every {@value #FLOOR_EVERY} rows of the
 * band with air between, so every chunk gets stalks, ordinary and great, whatever the generated claim holds. Its
 * writes are recorded and dropped, so nothing loads or changes in the world.
 */
public class ClaimCaveStalksTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClaimCaveStalksTest.class);
    private static final int LAYER = 1;
    private static final int FLOOR_EVERY = 20;
    private static final int SAMPLE_CHUNKS = 16;

    /** The writes the stand-in level records; it answers each as done. */
    private static final Set<String> WRITES = Set.of("setBlock", "removeBlock", "destroyBlock");

    /** What the stand-in level asks the real one: its shape, registries and seed, never a block. */
    private static final Set<String> FORWARDED =
            Set.of("dimensionType", "registryAccess", "enabledFeatures", "getSeed", "getSeaLevel", "getLevel");

    private record Write(BlockPos pos, BlockState state) {}

    /**
     * Every block the cave stalks write, ordinary column or great stalk, lies in the chunk they were placed for: a
     * write past the chunk's side lands in a neighbour that may be decorated before or after it, or wraps onto the far
     * side of the same chunk.
     */
    @GameTest
    public void theCaveStalksWriteOnlyInTheirChunk(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, LAYER);
        LayerChain.Band band = TestLayers.band(level, LAYER);
        PlacedFeature stalks = level.registryAccess()
                .lookupOrThrow(Registries.PLACED_FEATURE)
                .getValueOrThrow(ResourceKey.create(
                        Registries.PLACED_FEATURE,
                        Identifier.fromNamespaceAndPath("deepcharter", "fungal_stalks_cave")));
        Holder<Biome> claims = level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .getOrThrow(ResourceKey.create(
                        Registries.BIOME, Identifier.fromNamespaceAndPath("deepcharter", "topsoil_claims")));
        Block great = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("deepcharter", "great_stalk"));
        List<Write> writes = new ArrayList<>();
        // As decoration places it: the biome step checks that the zone's biome lists the feature.
        FeaturePlacer placer = new FeaturePlacer(
                standIn(level, band, claims, writes), level.getChunkSource().getGenerator());
        int written = 0;
        int greatBlocks = 0;
        int outside = 0;
        List<String> strays = new ArrayList<>();
        for (int dx = 0; dx < SAMPLE_CHUNKS; dx++) {
            for (int dz = 0; dz < SAMPLE_CHUNKS; dz++) {
                ChunkPos chunk = new ChunkPos(dx, dz);
                writes.clear();
                placer.placeWithBiomeCheck(stalks, RandomSource.create(chunk.pack()), chunk.getWorldPosition());
                for (Write write : writes) {
                    written++;
                    if (write.state().is(great)) {
                        greatBlocks++;
                    }
                    if (!ChunkPos.containing(write.pos()).equals(chunk)) {
                        outside++;
                        if (strays.size() < 8) {
                            strays.add(write.pos().toShortString() + " for chunk " + chunk);
                        }
                    }
                }
            }
        }
        LOGGER.info(
                "[claim-cave-stalks] {} blocks written in {} chunks, {} of them great stalk, {} outside their chunk",
                written,
                SAMPLE_CHUNKS * SAMPLE_CHUNKS,
                greatBlocks,
                outside);
        expect(helper, greatBlocks > 0, "no great stalk grew among %s blocks written", written);
        expect(
                helper,
                outside == 0,
                "the cave stalks wrote %s blocks outside their chunk, among them %s",
                outside,
                strays);
        helper.succeed();
    }

    /**
     * A level of floors of stone every {@link #FLOOR_EVERY} rows of {@code band} and air between, all of it
     * {@code biome}, that records its writes in {@code writes} instead of making them. It asks {@code level} only what
     * {@link #FORWARDED} names, and fails on any other read, so the test never reaches the generated world.
     */
    private static WorldGenLevel standIn(
            ServerLevel level, LayerChain.Band band, Holder<Biome> biome, List<Write> writes) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (WRITES.contains(name)) {
                writes.add(new Write(
                        (BlockPos) args[0],
                        name.equals("setBlock") ? (BlockState) args[1] : Blocks.AIR.defaultBlockState()));
                return true;
            }
            if (name.equals("getBlockState")) {
                int y = ((BlockPos) args[0]).getY();
                return Math.floorMod(y - band.minY(), FLOOR_EVERY) == 0
                        ? Blocks.STONE.defaultBlockState()
                        : Blocks.AIR.defaultBlockState();
            }
            if (name.equals("getFluidState")) {
                return ((WorldGenLevel) proxy).getBlockState((BlockPos) args[0]).getFluidState();
            }
            if (name.equals("getBiome")) {
                return biome;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            if (FORWARDED.contains(name) || method.getDeclaringClass() == Object.class) {
                try {
                    return method.invoke(level, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            throw new UnsupportedOperationException("the stand-in level does not answer " + method);
        };
        return (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, handler);
    }
}
