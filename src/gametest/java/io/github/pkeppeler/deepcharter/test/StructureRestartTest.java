package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.PendingStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.IsolatedBatches;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
 * #913: a chunk that generates before the colony's pad is chosen is held, undrawn, and must be drawn once the pad is chosen.
 *
 * <p>The production case is a server stop in between: the chunk was saved undrawn and reloads with {@code newlyGenerated} false, so
 * unless what is held survives, its structure is never drawn and the shell keeps the zone fill's gas and lava. The restart is
 * simulated by the real events a start fires ({@code SERVER_STARTED}, {@code BEGUN}) after the chunks were saved and unloaded.
 *
 * <p>The test case is a swap of the colony's record: chunks loaded in it are held while the real colony has a pad, and no event follows.
 * The next chunk load draws them. Each test is a batch of its own ({@link IsolatedBatches}): the drain is world-global.
 */
public class StructureRestartTest {
    private static final int TICKS = FarChunks.AWAIT_BUDGET_TICKS + 600;

    /** The sites of both layers in one cell, and the chunks they touch. */
    private record Cell(ServerLevel level, List<StructureSite> sites, Set<ChunkPos> chunks) {
        static Cell of(GameTestHelper helper, int cell) {
            BlockPos conduit = Colony.anchor(helper.getLevel().getServer(), ColonyAnchor.CONDUIT)
                    .orElseThrow(() -> failure(helper, "the colony was not built"));
            ServerLevel level = TestLayers.level(helper, 1);
            List<StructureSite> sites = new ArrayList<>();
            Set<ChunkPos> chunks = new LinkedHashSet<>();
            for (int layer = 1; layer <= 2; layer++) {
                LayerChain.Band band = TestLayers.band(level, layer);
                for (StructureKind kind : StructureKind.inLayer(layer)) {
                    StructureSite site =
                            StructureSite.in(level.getSeed(), kind, band.minY(), band.height(), cell, cell, conduit);
                    sites.add(site);
                    chunks.addAll(LayerStructuresTest.chunksOf(site));
                }
            }
            for (ChunkPos chunk : chunks) {
                if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                    throw failure(helper, "chunk %s was loaded already, so the test proves nothing", chunk);
                }
            }
            return new Cell(level, sites, chunks);
        }

        /** The server thread loads the chunks while no pad is chosen: each is held, undrawn. */
        void loadWithoutAPad(GameTestHelper helper) {
            WorldData.with(helper.getLevel().getServer(), ColonySite.TYPE, new ColonySite(), () -> {
                for (ChunkPos chunk : chunks) {
                    // sync-load: the chunks must load inside the swap, in this tick, to be held while no pad is chosen
                    level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
                }
                if (unmet(level, sites).isEmpty()) {
                    throw failure(helper, "the chunks were drawn before the colony had a pad");
                }
            });
        }
    }

    @GameTest(environment = IsolatedBatches.STRUCTURE_DRAIN_STARTED, maxTicks = TICKS)
    public void aChunkHeldBeforeThePadIsChosenIsDrawnWhenTheServerStartsAgain(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        restart(helper, 70, () -> ServerLifecycleEvents.SERVER_STARTED.invoker().onServerStarted(server));
    }

    @GameTest(environment = IsolatedBatches.STRUCTURE_DRAIN_BEGUN, maxTicks = TICKS)
    public void aChunkHeldBeforeThePadIsChosenIsDrawnWhenTheColonyBegins(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        restart(
                helper,
                72,
                () -> ColonyEvents.BEGUN
                        .invoker()
                        .onBegun(server, ColonySite.get(server).started().orElseThrow()));
    }

    /** Saves the held chunks undrawn, waits for them to unload, then runs the event of a start that finds the pad chosen. */
    private static void restart(GameTestHelper helper, int cellNumber, Runnable start) {
        Cell cell = Cell.of(helper, cellNumber);
        cell.loadWithoutAPad(helper);
        ServerLevel level = cell.level();
        FarChunks.Deadline deadline = FarChunks.deadline();
        boolean[] reloaded = {false};
        TestTicks.each(helper, () -> {
            if (!reloaded[0]) {
                boolean allUnloaded = cell.chunks().stream()
                        .allMatch(chunk -> level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null);
                deadline.await(helper, level, allUnloaded, () -> "the chunks did not unload");
                if (!allUnloaded) {
                    return;
                }
                start.run();
                reloaded[0] = true;
            }
            List<String> unmet = unmet(level, cell.sites());
            if (!unmet.isEmpty()) {
                throw failure(
                        helper,
                        "after a restart before the pad was chosen, the structures were never drawn: %s",
                        unmet);
            }
            helper.succeed();
        });
    }

    /** The chunks stay loaded and no event follows the end of the swap: loading any other chunk is what draws them. */
    @GameTest(environment = IsolatedBatches.STRUCTURE_DRAIN_SWAP, maxTicks = TICKS)
    public void chunksHeldDuringASwapOfTheColonyAreDrawnByTheNextChunkLoad(GameTestHelper helper) {
        Cell cell = Cell.of(helper, 71);
        cell.loadWithoutAPad(helper);
        ServerLevel level = cell.level();
        // The pad is back. Any chunk load now finds chunks held and a pad chosen.
        // sync-load: the test is that a chunk load on the server thread, in this tick, draws the held chunks
        level.getChunk(3000, 3000, ChunkStatus.FULL);
        TestTicks.each(helper, () -> {
            // The drain runs at the end of a tick, so the first tick sees them undrawn; the timeout fails a test that
            // never draws.
            if (unmet(level, cell.sites()).isEmpty()) {
                helper.succeed();
            }
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
