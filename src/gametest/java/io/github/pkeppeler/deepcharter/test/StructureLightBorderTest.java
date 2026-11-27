package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.test.support.BlockLightOracle;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LightSyncSends;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * The block light round a lamp of a layer structure that stands at a chunk border (#824). A player who tracks the lamp's chunk and its
 * neighbour across the border is sent both, with the structure drawn, and the light settles. Then every lamp of the two chunks is
 * removed and {@link LightSync} holds the chunks, as a carve does. Then:
 *
 * <ul>
 *   <li>the light the server stores in both chunks is the light of the blocks, which {@link BlockLightOracle} works out without the
 *       light engine, checked before the lamps are removed;
 *   <li>{@link LightSync} sends the player, once the light is done, a light update that carries every section of each chunk whose
 *       stored light changed. Vanilla sends a light update only for a chunk that ticks, and drops the change of a chunk that does
 *       not, so a client that already holds a chunk kept the old light when a neighbour's light spilled into it later: a lamp's
 *       spill missing, or the glow of a lava the draw sealed over still there. The test reads what {@code LightSync} sends through
 *       {@link LightSyncSends}, so a vanilla update of a ticking chunk cannot stand in for it.
 * </ul>
 */
public class StructureLightBorderTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    /** Spacing cells {@code BASE_CELL} and up, along X, of the sample: cells no other test draws. */
    private static final int BASE_CELL = 40;

    private static final int CELL_SEARCH = 400;
    /** Block light spreads this far, so a lamp this near a border (counted to the far block of its side) lights the neighbour. */
    private static final int NEAR_BORDER = 3;

    private static final int REACH = 15;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 600;
    /** Distance east of the wreck site to put a faked Conduit, clear of the site's shell and the casing. */
    private static final int FAKE_CONDUIT_OFFSET = 48;

    /** Server ticks that the callbacks of the light engine's last update get to run on the main thread. */
    private static final int SETTLE_TICKS = 20;

    /** Which lamp the case is about. */
    private enum Lamp {
        /** The Punch Clock hall's sea lantern. */
        SEA_LANTERN(StructureKind.PUNCH_CLOCK),
        /** The lava of the Kells' shaft's gaps. */
        KELLS_LAVA(StructureKind.KELLS_SHAFT),
        /** The lit candle of a shaft's niche. */
        CANDLE(StructureKind.TOPSOIL_SHAFT),
        /** The lantern in PROSPECTOR-0002's bay, drawn only at the famous wreck. */
        FAMOUS_LANTERN(StructureKind.WRECK);

        private final StructureKind kind;

        Lamp(StructureKind kind) {
            this.kind = kind;
        }
    }

    /** The two chunks across a border, the lamp's and its neighbour. */
    private record Border(ChunkPos lampChunk, ChunkPos neighbour) {}

    /** Where a case draws: the two chunks across the border, the lamp's own and the neighbour, and the rows to compare. */
    private record Placement(
            StructureSite site,
            Optional<BlockPos> fakeConduit,
            ChunkPos lampChunk,
            ChunkPos neighbour,
            int minY,
            int maxY) {
        List<ChunkPos> pair() {
            return List.of(lampChunk, neighbour);
        }

        /** The chunks of the pair and the ring of 8 round each. */
        List<ChunkPos> withRing() {
            int minX = Math.min(lampChunk.x(), neighbour.x()) - 1;
            int maxX = Math.max(lampChunk.x(), neighbour.x()) + 1;
            int minZ = Math.min(lampChunk.z(), neighbour.z()) - 1;
            int maxZ = Math.max(lampChunk.z(), neighbour.z()) + 1;
            List<ChunkPos> ring = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    ring.add(new ChunkPos(x, z));
                }
            }
            return ring;
        }

        BoundingBox compared() {
            return new BoundingBox(
                    Math.min(lampChunk.getMinBlockX(), neighbour.getMinBlockX()),
                    minY,
                    Math.min(lampChunk.getMinBlockZ(), neighbour.getMinBlockZ()),
                    Math.max(lampChunk.getMaxBlockX(), neighbour.getMaxBlockX()),
                    maxY,
                    Math.max(lampChunk.getMaxBlockZ(), neighbour.getMaxBlockZ()));
        }
    }

    /**
     * The border a lamp's light crosses, as the chunk of the lamp and the one across the border, for {@code site}, or empty when the
     * site's lamp is not at a border. The positions restate the blueprint ({@link StructureKind}); a drift fails the case's own
     * check that light crosses.
     */
    private static Optional<Border> pair(Lamp lamp, StructureSite site) {
        BlockPos o = site.origin();
        // The structure's own axes: u along its long axis, v across it, as StructurePlan.set maps them.
        int oU = site.alongZ() ? o.getZ() : o.getX();
        int oV = site.alongZ() ? o.getX() : o.getZ();
        return switch (lamp) {
            case SEA_LANTERN, FAMOUS_LANTERN -> {
                // The lantern is on the across axis: the hall (v -8..8) and the bay (v -6..6) reach past a border 3
                // blocks off.
                int lampV = lamp == Lamp.SEA_LANTERN ? oV : oV + 2;
                int lampU = lamp == Lamp.SEA_LANTERN ? oU - 7 : oU + 3;
                yield acrossBorder(site, lampU, lampV);
            }
            case CANDLE -> {
                // The candle is at u = 2 and v = 1; its air is on the -u side, so the border must be at the lamp's
                // own edge.
                int lampU = oU + 2;
                if ((lampU & 15) != 0) {
                    yield Optional.empty();
                }
                yield Optional.of(chunks(site, lampU, oV + 1, lampU - 1, oV + 1));
            }
            case KELLS_LAVA -> {
                // The hollow is 2 x 2 at -1..0: it straddles a border when the border falls between -1 and 0.
                if ((oU & 15) == 0) {
                    yield Optional.of(chunks(site, oU - 1, oV, oU, oV));
                }
                if ((oV & 15) == 0) {
                    yield Optional.of(chunks(site, oU, oV - 1, oU, oV));
                }
                yield Optional.empty();
            }
        };
    }

    /** The lamp at ({@code lampU}, {@code lampV}) is within {@value #NEAR_BORDER} blocks of a border on the across axis. */
    private static Optional<Border> acrossBorder(StructureSite site, int lampU, int lampV) {
        int edge = lampV & 15;
        if (edge <= NEAR_BORDER) {
            return Optional.of(chunks(site, lampU, lampV, lampU, lampV - edge - 1));
        }
        if (edge >= 15 - NEAR_BORDER) {
            return Optional.of(chunks(site, lampU, lampV, lampU, lampV + (15 - edge) + 1));
        }
        return Optional.empty();
    }

    /** The chunks of two positions given in the structure's axes, the first the lamp's. */
    private static Border chunks(StructureSite site, int u1, int v1, int u2, int v2) {
        return new Border(chunkAt(site, u1, v1), chunkAt(site, u2, v2));
    }

    private static ChunkPos chunkAt(StructureSite site, int u, int v) {
        return new ChunkPos((site.alongZ() ? v : u) >> 4, (site.alongZ() ? u : v) >> 4);
    }

    /** The {@code index}th cell, from {@link #BASE_CELL}, whose lamp stands at a border: each index is a cell no other index has. */
    private static Placement place(GameTestHelper helper, Lamp lamp, int index) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        CampaignBands.Band band = CampaignBands.layer(lamp.kind.layer());
        BlockPos realConduit = Colony.anchor(server, ColonyAnchor.CONDUIT)
                .orElseThrow(() -> failure(helper, "the colony was not built"));
        int skipped = 0;
        for (int cell = BASE_CELL; cell < BASE_CELL + CELL_SEARCH; cell++) {
            // The famous wreck is the one nearest the Conduit: put a Conduit of the test's own beside this cell's site.
            StructureSite plain = StructureSite.in(
                    level.getSeed(), lamp.kind, band.minY(), band.height(), cell, BASE_CELL, realConduit);
            Optional<BlockPos> fake = lamp == Lamp.FAMOUS_LANTERN
                    ? Optional.of(plain.origin().offset(FAKE_CONDUIT_OFFSET, 0, 0))
                    : Optional.empty();
            BlockPos conduit = fake.orElse(realConduit);
            StructureSite site =
                    StructureSite.in(level.getSeed(), lamp.kind, band.minY(), band.height(), cell, BASE_CELL, conduit);
            if (!site.equals(plain)
                    || (lamp == Lamp.FAMOUS_LANTERN
                            && !site.equals(LayerStructures.nearest(
                                    level.getSeed(), lamp.kind, band.minY(), band.height(), conduit, conduit)))) {
                continue;
            }
            Optional<Border> pair = pair(lamp, site);
            if (pair.isEmpty() || skipped++ < index) {
                continue;
            }
            int baseY = site.origin().getY();
            int top = lamp == Lamp.KELLS_LAVA ? baseY + site.height() : baseY + 5;
            return new Placement(
                    site,
                    fake,
                    pair.get().lampChunk(),
                    pair.get().neighbour(),
                    Math.max(
                            level.getMinY(), baseY - REACH), // level-height: clamps light reach to the level's own rows
                    Math.min(level.getMaxY(), top + REACH)); // level-height: clamps light reach to the level's own rows
        }
        throw failure(helper, "no cell of %d from %d has a %s at a chunk border", CELL_SEARCH, BASE_CELL, lamp);
    }

    /** The colony as the test world has it, with its Conduit moved to {@code conduit}, so a wreck site beside it is the famous one. */
    private static ColonySite withConduit(MinecraftServer server, BlockPos conduit) {
        CompoundTag moved = ((CompoundTag) ColonySite.CODEC
                        .encodeStart(NbtOps.INSTANCE, ColonySite.get(server))
                        .getOrThrow())
                .copy();
        moved.getCompound("colony")
                .orElseThrow()
                .getCompound("anchors")
                .orElseThrow()
                .put("conduit", new IntArrayTag(new int[] {conduit.getX(), conduit.getY(), conduit.getZ()}));
        return ColonySite.CODEC.parse(NbtOps.INSTANCE, moved).getOrThrow();
    }

    private static boolean holdsEnded(List<ChunkPos> chunks) {
        return LightSync.heldChunks().stream()
                .noneMatch(line -> chunks.stream().anyMatch(chunk -> line.contains(chunk.toString())));
    }

    private static void run(GameTestHelper helper, Lamp lamp) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Placement placement = place(helper, lamp, lamp.ordinal());
        List<ChunkPos> pair = placement.pair();
        MockPlayer player = MockPlayers.join(helper, "border-" + lamp.ordinal());
        player.player().setGameMode(GameType.SPECTATOR);
        UUID id = player.player().getUUID();
        LightSyncSends.watch(id);
        FarChunks.Deadline deadline = FarChunks.deadline(60);
        FarChunks.Deadline loadDeadline = FarChunks.deadline();
        int[] phase = {0};
        int[] waited = {0};
        int[][][] before = {null};
        boolean[] ended = {false};
        TestTicks.each(helper, () -> {
            if (ended[0]) {
                return;
            }
            try {
                switch (phase[0]) {
                    case 0 -> {
                        // Only the conduit case loads here: its site swap lasts one tick.
                        if (placement.fakeConduit().isPresent()) {
                            inContext(
                                    placement,
                                    server,
                                    () -> placement.withRing().forEach(chunk -> {
                                        FarChunks.force(helper, level, chunk.x(), chunk.z());
                                        // sync-load: the conduit swap of inContext lasts one tick, and the chunk must
                                        // generate inside it
                                        level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
                                    }));
                        } else {
                            placement.withRing().forEach(chunk -> FarChunks.force(helper, level, chunk.x(), chunk.z()));
                        }
                        phase[0] = 1;
                    }
                    case 1 -> {
                        boolean loaded = placement.withRing().stream()
                                .allMatch(chunk -> level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null);
                        loadDeadline.await(helper, level, loaded, () -> "the chunks round " + pair + " did not load");
                        if (!loaded) {
                            return;
                        }
                        // Chunks are sent once they tick: a player who comes into view of them now is sent both.
                        BlockPos at = new BlockPos(
                                placement.lampChunk().getMiddleBlockX(),
                                placement.site().origin().getY(),
                                placement.lampChunk().getMiddleBlockZ());
                        if (!pair.stream().allMatch(chunk -> level.isPositionEntityTicking(chunk.getWorldPosition()))) {
                            deadline.await(helper, level, false, () -> "the chunks " + pair + " do not tick");
                            return;
                        }
                        player.teleportTo(level, Vec3.atBottomCenterOf(at), 0f, 0f);
                        phase[0] = 2;
                    }
                    case 2 -> {
                        if (!player.chunksSent().containsAll(pair) || !holdsEnded(pair)) {
                            deadline.await(helper, level, false, () -> "the player was not sent " + pair + " in time");
                            return;
                        }
                        if (waited[0]++ < SETTLE_TICKS) {
                            return;
                        }
                        checkLight(helper, level, placement, lamp);
                        before[0] = pair.stream()
                                .map(chunk -> storedLight(level, chunk, placement))
                                .toArray(int[][]::new);
                        removeLamps(level, placement);
                        waited[0] = 0;
                        phase[0] = 3;
                    }
                    default -> {
                        if (!holdsEnded(pair)) {
                            deadline.await(helper, level, false, () -> "the hold of " + pair + " did not end");
                            return;
                        }
                        if (waited[0]++ < SETTLE_TICKS) {
                            return;
                        }
                        checkSent(helper, level, placement, id, Objects.requireNonNull(before[0]), lamp);
                        ended[0] = true;
                        LightSyncSends.unwatch(id);
                        helper.succeed();
                    }
                }
            } catch (RuntimeException e) {
                ended[0] = true;
                LightSyncSends.unwatch(id);
                throw e;
            }
        });
    }

    /** Removes every block of the pair that gives light, and holds the sending of its chunks as a carve of the box does. */
    private static void removeLamps(ServerLevel level, Placement placement) {
        BoundingBox box = placement.compared();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<BlockPos> lamps = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    if (level.getBlockState(pos.set(x, y, z)).getLightEmission() > 0) {
                        lamps.add(pos.immutable());
                    }
                }
            }
        }
        lamps.forEach(lamp -> level.setBlock(lamp, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
        if (!lamps.isEmpty()) {
            LightSync.holdSendUntilLit(
                    level,
                    new BlockPos(
                            lamps.stream().mapToInt(BlockPos::getX).min().orElseThrow(),
                            lamps.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                            lamps.stream().mapToInt(BlockPos::getZ).min().orElseThrow()),
                    new BlockPos(
                            lamps.stream().mapToInt(BlockPos::getX).max().orElseThrow(),
                            lamps.stream().mapToInt(BlockPos::getY).max().orElseThrow(),
                            lamps.stream().mapToInt(BlockPos::getZ).max().orElseThrow()));
        }
    }

    /** The block light the engine stores for every cell of {@code chunk} in the rows of the placement, in x, z, y order. */
    private static int[] storedLight(ServerLevel level, ChunkPos chunk, Placement placement) {
        int rows = placement.maxY() - placement.minY() + 1;
        int[] light = new int[16 * 16 * rows];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int at = 0;
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int y = placement.minY(); y <= placement.maxY(); y++) {
                    light[at++] = level.getLightEngine()
                            .getLayerListener(LightLayer.BLOCK)
                            .getLightValue(pos.set(x, y, z));
                }
            }
        }
        return light;
    }

    /** Runs {@code body}, with the test's own Conduit as the colony's if the case needs the famous wreck. */
    private static void inContext(Placement placement, MinecraftServer server, Runnable body) {
        placement
                .fakeConduit()
                .ifPresentOrElse(
                        conduit -> WorldData.with(server, ColonySite.TYPE, withConduit(server, conduit), body), body);
    }

    /** The server stores the light of the blocks in the pair, and light crosses between the two chunks. */
    private static void checkLight(GameTestHelper helper, ServerLevel level, Placement placement, Lamp lamp) {
        BoundingBox box = placement.compared();
        BlockLightOracle.Field expected = BlockLightOracle.expected(level, box);
        String where = lamp + " at " + placement.site().origin().toShortString() + " (lamp chunk "
                + placement.lampChunk() + ", neighbour " + placement.neighbour() + ")";
        // The case proves nothing unless light crosses the border: a chunk of the pair must hold light that comes from
        // the other.
        long crossed = 0;
        for (ChunkPos chunk : placement.pair()) {
            BlockLightOracle.Field own = BlockLightOracle.expected(
                    level, box, pos -> ChunkPos.containing(pos).equals(chunk));
            for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
                for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (expected.at(pos) > own.at(pos)) {
                            crossed++;
                        }
                    }
                }
            }
        }
        if (crossed == 0) {
            throw failure(helper, "%s: no light crosses between the two chunks, so the case proves nothing", where);
        }
        List<BlockLightOracle.Mismatch> stored = BlockLightOracle.mismatches(level, expected);
        if (!stored.isEmpty()) {
            throw failure(
                    helper,
                    "%s: the server stores block light the blocks do not give in %d cells; the first: %s",
                    where,
                    stored.size(),
                    stored.subList(0, Math.min(6, stored.size())));
        }
    }

    /** {@link LightSync} sent the player every section of each chunk of the pair whose stored light changed since {@code before}. */
    private static void checkSent(
            GameTestHelper helper, ServerLevel level, Placement placement, UUID player, int[][] before, Lamp lamp) {
        List<ChunkPos> pair = placement.pair();
        int rows = placement.maxY() - placement.minY() + 1;
        int minLightSection = level.getLightEngine().getMinLightSection();
        for (int c = 0; c < pair.size(); c++) {
            ChunkPos chunk = pair.get(c);
            int[] after = storedLight(level, chunk, placement);
            BitSet changedSections = new BitSet();
            int changed = 0;
            for (int i = 0; i < after.length; i++) {
                if (after[i] != before[c][i]) {
                    changed++;
                    changedSections.set(((placement.minY() + i % rows) >> 4) - minLightSection);
                }
            }
            String where = lamp + " at " + placement.site().origin().toShortString() + ", chunk " + chunk;
            if (changed == 0) {
                throw failure(
                        helper, "%s: removing the lamps changed no light in it, so the case proves nothing", where);
            }
            BitSet sent = LightSyncSends.sectionsCarried(player, chunk);
            BitSet unsent = (BitSet) changedSections.clone();
            unsent.andNot(sent);
            if (!unsent.isEmpty()) {
                throw failure(
                        helper,
                        "%s: the stored light changed in %d cells, a player who had the chunk was not sent the"
                            + " sections %s (counted from the lowest light section) by the hold, so the client keeps"
                            + " the old light",
                        where,
                        changed,
                        unsent);
            }
        }
    }

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aSeaLanternAtABorderIsSentToAPlayerWhoHasBothChunks(GameTestHelper helper) {
        run(helper, Lamp.SEA_LANTERN);
    }

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aKellsLavaAtABorderIsSentToAPlayerWhoHasBothChunks(GameTestHelper helper) {
        run(helper, Lamp.KELLS_LAVA);
    }

    @GameTest(environment = CampaignEnvironments.A, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aShaftCandleAtABorderIsSentToAPlayerWhoHasBothChunks(GameTestHelper helper) {
        run(helper, Lamp.CANDLE);
    }

    @GameTest(environment = CampaignEnvironments.SYNC_CHUNK_LOAD, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theFamousWrecksLanternAtABorderIsSentToAPlayerWhoHasBothChunks(GameTestHelper helper) {
        run(helper, Lamp.FAMOUS_LANTERN);
    }
}
