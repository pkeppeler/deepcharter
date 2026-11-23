package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.PendingStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * #913, the production case: a chunk that generates before the colony's pad is chosen is held, undrawn. The server stops before the
 * pad is chosen; the chunk was saved undrawn and reloads with {@code newlyGenerated} false, so unless what is held survives the stop,
 * its structure is never drawn and the shell keeps the zone fill's gas and lava.
 *
 * <p>The restart is simulated by what a start does: the chunks were saved and unloaded, and {@code LayerStructures.drawPending} runs
 * with the pad chosen, as {@code SERVER_STARTED} runs it.
 */
public class StructureRestartTest {
    private static final int CELL = 70;

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void aChunkParkedBeforeThePadIsChosenIsDrawnAfterARestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos conduit = Colony.anchor(server, ColonyAnchor.CONDUIT)
                .orElseThrow(() -> failure(helper, "the colony was not built"));
        ServerLevel level = TestLayers.level(helper, 1);
        List<StructureSite> sites = new ArrayList<>();
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (int layer = 1; layer <= 2; layer++) {
            LayerChain.Band band = TestLayers.band(level, layer);
            for (StructureKind kind : StructureKind.inLayer(layer)) {
                StructureSite site =
                        StructureSite.in(level.getSeed(), kind, band.minY(), band.height(), CELL, CELL, conduit);
                sites.add(site);
                chunks.addAll(LayerStructuresTest.chunksOf(site));
            }
        }
        for (ChunkPos chunk : chunks) {
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                throw failure(helper, "chunk %s was loaded already, so the test proves nothing", chunk);
            }
        }
        // The server thread loads the chunks while no pad is chosen: each is parked, undrawn.
        WorldData.with(server, ColonySite.TYPE, new ColonySite(), () -> {
            for (ChunkPos chunk : chunks) {
                level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
            }
            List<String> unmet = unmet(level, sites);
            if (unmet.isEmpty()) {
                throw failure(helper, "the chunks were drawn before the colony had a pad");
            }
        });
        FarChunks.Deadline deadline = FarChunks.deadline();
        boolean[] reloaded = {false};
        TestTicks.each(helper, () -> {
            if (!reloaded[0]) {
                boolean allUnloaded = chunks.stream()
                        .allMatch(chunk -> level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null);
                deadline.await(helper, level, allUnloaded, () -> "the chunks did not unload");
                if (!allUnloaded) {
                    return;
                }
                // The server starts with the pad chosen (the colony's own record is back); the chunks load from disk.
                LayerStructures.drawPending(server);
                for (ChunkPos chunk : chunks) {
                    level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
                }
                reloaded[0] = true;
            }
            List<String> unmet = unmet(level, sites);
            if (!unmet.isEmpty()) {
                throw failure(
                        helper,
                        "after a restart before the pad was chosen, the structures were never drawn: %s",
                        unmet);
            }
            helper.succeed();
        });
    }

    /** The saved form is pinned with literal values: a held chunk must survive a stop, so the format is permanent. */
    @GameTest
    public void thePendingChunksAreSavedAsVersionOneAndReadBackWhole(GameTestHelper helper) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("x", 3);
        chunk.putInt("z", -4);
        CompoundTag level = new CompoundTag();
        level.putString("dimension", "deepcharter:layer_1");
        ListTag chunks = new ListTag();
        chunks.add(chunk);
        level.put("chunks", chunks);
        ListTag levels = new ListTag();
        levels.add(level);
        CompoundTag saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.put("levels", levels);
        PendingStructures loaded =
                PendingStructures.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        if (!saved.equals(
                PendingStructures.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow())) {
            throw failure(helper, "the pending chunks were not written back as they were read");
        }
        helper.succeed();
    }

    @GameTest
    public void unreadablePendingChunksNeverThrowFromTheStartOrTheBegin(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Map<String, Runnable> paths = new LinkedHashMap<>();
        paths.put("draw pending", () -> LayerStructures.drawPending(server));
        UnreadableChecks.assertSavedDataNoThrow(helper, "pending structures", server, PendingStructures.TYPE, paths);
        helper.succeed();
    }

    private static List<String> unmet(ServerLevel level, List<StructureSite> sites) {
        List<String> unmet = new ArrayList<>();
        for (StructureSite site : sites) {
            LayerChain.Band band = TestLayers.band(level, site.kind().layer());
            List<String> missing = LayerStructuresTest.unmet(
                    level, band.minY(), band.height(), site, LayerStructuresTest.census(level, site));
            if (!missing.isEmpty()) {
                unmet.add(site.kind() + " at " + site.origin().toShortString() + ": " + missing);
            }
        }
        return unmet;
    }
}
