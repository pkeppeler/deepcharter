package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.CampaignBands.Band;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
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
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/**
 * Server GameTests for when a chunk of the campaign world draws its structure sites (#799, ADR 0025): once the colony's pad is chosen, not
 * once the colony is finished. {@code ColonyBuilder} loads the pad's chunks while it builds, and in the campaign world each of them holds
 * both layer bands, so a chunk that waited for the finished colony would lose its sites for good. Spacing cells 6 and 7 of
 * {@code deepcharter:campaign_sample} are this class's.
 */
public class BandStructuresTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = 400;
    /** The cell that a chunk loaded after the pad was chosen draws. */
    private static final int PAD_CELL = -12;
    /** The cell that a chunk loaded before the pad was chosen draws, when the pad is. */
    private static final int EARLY_CELL = -13;

    /** The colony as it stands while its build is under way: the pad chosen and every anchor known, nothing finished. */
    private static ColonySite begun(MinecraftServer server) {
        CompoundTag unfinished = ((CompoundTag) ColonySite.CODEC
                        .encodeStart(NbtOps.INSTANCE, ColonySite.get(server))
                        .getOrThrow())
                .copy();
        unfinished.getCompound("colony").orElseThrow().putBoolean("finished", false);
        return ColonySite.CODEC.parse(NbtOps.INSTANCE, unfinished).getOrThrow();
    }

    private static BlockPos conduit(GameTestHelper helper) {
        return Colony.anchor(helper.getLevel().getServer(), ColonyAnchor.CONDUIT)
                .orElseThrow(() -> failure(helper, "the colony was not built"));
    }

    /** One site of each kind of both layers in {@code cell}, each with its band. */
    private static Map<StructureSite, Band> sitesIn(GameTestHelper helper, int cell) {
        ServerLevel level = helper.getLevel();
        Map<StructureSite, Band> sites = new LinkedHashMap<>();
        for (int number = 1; number <= CampaignBands.LAYERS.size(); number++) {
            Band band = CampaignBands.layer(number);
            for (StructureKind kind : StructureKind.inLayer(number)) {
                sites.put(
                        StructureSite.in(
                                level.getSeed(), kind, band.minY(), band.height(), cell, cell, conduit(helper)),
                        band);
            }
        }
        return sites;
    }

    private static Set<ChunkPos> chunksOf(Map<StructureSite, Band> sites) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        sites.keySet().forEach(site -> chunks.addAll(LayerStructuresTest.chunksOf(site)));
        return chunks;
    }

    private static void requireUnloaded(GameTestHelper helper, ServerLevel level, Set<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                throw failure(helper, "chunk %s was loaded already, so the test proves nothing", chunk);
            }
        }
    }

    private static void loadAll(ServerLevel level, Set<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            // sync-load: the case is the chunk loading in the same tick as the colony swap of WorldData.with, so its
            // worldgen reads the swapped site
            level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
        }
    }

    /** What each site still lacks, as text; empty when every one stands. Reads blocks, so the chunks must be loaded. */
    private static List<String> unmet(ServerLevel level, Map<StructureSite, Band> sites) {
        List<String> unmet = new ArrayList<>();
        sites.forEach((site, band) -> {
            List<String> missing = LayerStructuresTest.unmet(
                    level, band.minY(), band.height(), site, LayerStructuresTest.census(level, site));
            if (!missing.isEmpty()) {
                unmet.add(site.kind() + " at " + site.origin().toShortString() + ": " + missing);
            }
        });
        return unmet;
    }

    /**
     * A chunk that loads while the colony is begun and not finished (the pad's own, during the build) draws every site it holds, in both
     * bands. The sites are those of the finished colony: the pad and the anchors do not move.
     */
    @GameTest(environment = CampaignEnvironments.SYNC_CHUNK_LOAD, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aPadChunkLoadedBeforeTheBuildFinishesGetsItsSitesWhole(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Map<StructureSite, Band> sites = sitesIn(helper, PAD_CELL);
        Set<ChunkPos> chunks = chunksOf(sites);
        requireUnloaded(helper, level, chunks);
        WorldData.with(server, ColonySite.TYPE, begun(server), () -> {
            loadAll(level, chunks);
            List<String> unmet = unmet(level, sites);
            if (!unmet.isEmpty()) {
                throw failure(helper, "a chunk loaded while the colony was being built lost its sites: %s", unmet);
            }
        });
        helper.succeed();
    }

    /** PROSPECTOR-0002's site is known as soon as the pad is chosen, so its bay is drawn with its lamp and its note whatever chunk comes first. */
    @GameTest(environment = CampaignEnvironments.B, dimension = SAMPLE)
    public void theProspectorsSiteIsKnownOnceThePadIsChosen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        StructureSite finished = LayerStructures.prospector(level)
                .orElseThrow(() -> failure(helper, "the finished colony has no Prospector site"));
        WorldData.with(server, ColonySite.TYPE, begun(server), () -> {
            if (!LayerStructures.prospector(level).equals(java.util.Optional.of(finished))) {
                throw failure(
                        helper,
                        "with the colony begun, PROSPECTOR-0002's site is %s, expected %s",
                        LayerStructures.prospector(level),
                        finished);
            }
        });
        helper.succeed();
    }

    /**
     * A chunk that loads before the pad is chosen (the build reads the ground of candidate pads first) is held, and drawn, once, when the
     * pad is chosen: the chunk has no sites before, and all of them after.
     */
    @GameTest(environment = CampaignEnvironments.SYNC_CHUNK_LOAD, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aChunkLoadedBeforeThePadIsChosenIsDrawnWhenItIs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Map<StructureSite, Band> sites = sitesIn(helper, EARLY_CELL);
        Set<ChunkPos> chunks = chunksOf(sites);
        requireUnloaded(helper, level, chunks);
        ColonySite chosen = begun(server);
        WorldData.with(server, ColonySite.TYPE, new ColonySite(), () -> {
            loadAll(level, chunks);
            if (unmet(level, sites).isEmpty()) {
                throw failure(helper, "the chunks were drawn before the colony had a pad");
            }
            WorldData.replace(server, ColonySite.TYPE, chosen);
            ColonyEvents.BEGUN.invoker().onBegun(server, chosen.started().orElseThrow());
            List<String> unmet = unmet(level, sites);
            if (!unmet.isEmpty()) {
                throw failure(helper, "the chunks held until the pad was chosen were not drawn: %s", unmet);
            }
            // Each held chunk is drawn once: a second choice of the pad, or a second release, places no second pod.
            ColonyEvents.BEGUN.invoker().onBegun(server, chosen.started().orElseThrow());
            sites.keySet().stream()
                    .filter(site -> site.kind() == StructureKind.WRECK)
                    .forEach(site -> {
                        // entity-query: a test reads the pods of a bay it generated, in a chunk it loaded this tick
                        int pods = level.getEntitiesOfClass(PodEntity.class, new AABB(site.origin()).inflate(3))
                                .size();
                        if (pods != 1) {
                            throw failure(
                                    helper,
                                    "the wreck bay at %s holds %d pods after the pad was chosen twice",
                                    site.origin().toShortString(),
                                    pods);
                        }
                    });
        });
        helper.succeed();
    }
}
