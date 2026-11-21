package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.test.support.BlockLightOracle;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The block light round a lamp of a layer structure that stands at a chunk border (#824), in each order of the lamp's chunk and its
 * neighbour across the border. One chunk of the pair, {@code held}, is loaded, lit and let go out to its clients, and does not tick (a
 * ticket of radius 0 holds it, as a chunk is held while its neighbour is still generating). The other, {@code drawn}, is not generated;
 * the test loads it, which draws its part of the structure, and lets the light engine finish. Then:
 *
 * <ul>
 *   <li>the light the server stores in both chunks is the light of the blocks, which {@link BlockLightOracle} works out without the
 *       light engine;
 *   <li>if the light stored in {@code held} changed under the draw, {@code held} is marked for a light update to its clients. Vanilla
 *       marks a changed light section only while its chunk ticks ({@code ChunkHolder.sectionLightChanged}), so a client that was sent
 *       the chunk before the draw kept the old light: a lamp's spill missing, or the glow of a lava the draw sealed over still there.
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
                // The candle is at u = 2 and v = 1; its air is on the -u side, so the border must be at the lamp's own
                // edge.
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

    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
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

    /** What a case holds while it runs: tickets to let go. */
    private static final class Held {
        private final ServerLevel level;
        private final List<ChunkPos> chunks = new ArrayList<>();

        Held(ServerLevel level) {
            this.level = level;
        }

        /** Loads {@code chunk} to the full status and holds it loaded, not ticking. */
        void load(ChunkPos chunk) {
            if (!chunks.contains(chunk)) {
                level.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, chunk, 0);
                chunks.add(chunk);
            }
            level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
        }

        void releaseAll() {
            chunks.forEach(chunk -> level.getChunkSource().removeTicketWithRadius(TicketType.PLAYER_LOADING, chunk, 0));
            chunks.clear();
        }
    }

    /** Which chunk of the pair is held, and so sent, before the other is drawn. */
    private enum Sent {
        /** The lamp's chunk is drawn after its neighbour went out: the neighbour's light changes under it. */
        NEIGHBOUR,
        /** The neighbour is generated after the lamp's chunk went out. */
        LAMP_CHUNK
    }

    /** Server ticks that the callbacks of the light engine's last update get to run on the main thread. */
    private static final int SETTLE_TICKS = 20;

    private static void run(GameTestHelper helper, Lamp lamp, Sent sent) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Placement placement = place(helper, lamp, lamp.ordinal() * 2 + sent.ordinal());
        ChunkPos held = sent == Sent.NEIGHBOUR ? placement.neighbour() : placement.lampChunk();
        ChunkPos drawn = sent == Sent.NEIGHBOUR ? placement.lampChunk() : placement.neighbour();
        Held tickets = new Held(level);
        CompletableFuture<?>[] settling = {CompletableFuture.completedFuture(null)};
        FarChunks.Deadline deadline = FarChunks.deadline(30);
        int[] phase = {0};
        int[] waited = {0};
        int[][] before = {null};
        TestTicks.each(helper, () -> {
            try {
                if (!(settling[0].isDone() && !level.getLightEngine().hasLightWork())) {
                    if (settling[0].isDone()) {
                        settling[0] = lightTasks(level, held);
                    }
                    deadline.await(helper, level, false, () -> "the light engine did not finish in phase " + phase[0]);
                    return;
                }
                switch (phase[0]) {
                    case 0 -> {
                        inContext(placement, server, () -> tickets.load(held));
                        phase[0] = 1;
                        settling[0] = lightTasks(level, held);
                    }
                    case 1 -> {
                        // The chunk's own generation has told its holder what changed; what is left to tell starts
                        // here.
                        if (waited[0]++ < SETTLE_TICKS) {
                            return;
                        }
                        if (level.getChunkSource().getChunkNow(drawn.x(), drawn.z()) != null) {
                            throw failure(helper, "chunk %s is generated already, so the case proves nothing", drawn);
                        }
                        ChunkHolder holder = holder(helper, level, held);
                        holder.broadcastChanges(
                                Objects.requireNonNull(level.getChunkSource().getChunkNow(held.x(), held.z())));
                        before[0] = storedLight(level, held, placement);
                        phase[0] = 2;
                    }
                    case 2 -> {
                        inContext(placement, server, () -> {
                            tickets.load(drawn);
                            placement.withRing().forEach(tickets::load);
                        });
                        waited[0] = 0;
                        phase[0] = 3;
                        settling[0] = lightTasks(level, held);
                    }
                    default -> {
                        if (waited[0]++ < SETTLE_TICKS) {
                            return;
                        }
                        compare(helper, level, placement, held, Objects.requireNonNull(before[0]), lamp, sent);
                        tickets.releaseAll();
                        helper.succeed();
                    }
                }
            } catch (RuntimeException e) {
                tickets.releaseAll();
                throw e;
            }
        });
    }

    private static ChunkHolder holder(GameTestHelper helper, ServerLevel level, ChunkPos chunk) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(chunk.pack());
        if (holder == null) {
            throw failure(helper, "chunk %s has no holder", chunk);
        }
        return holder;
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

    private static CompletableFuture<?> lightTasks(ServerLevel level, ChunkPos chunk) {
        return ((ThreadedLevelLightEngine) level.getLightEngine()).waitForPendingTasks(chunk.x(), chunk.z());
    }

    private static void compare(
            GameTestHelper helper,
            ServerLevel level,
            Placement placement,
            ChunkPos held,
            int[] before,
            Lamp lamp,
            Sent sent) {
        BoundingBox box = placement.compared();
        BlockLightOracle.Field expected = BlockLightOracle.expected(level, box);
        // The case proves nothing unless light crosses the border: a chunk of the pair must hold light that comes from
        // the other.
        long crossing = 0;
        for (ChunkPos chunk : List.of(placement.lampChunk(), placement.neighbour())) {
            BlockLightOracle.Field own = BlockLightOracle.expected(
                    level, box, pos -> ChunkPos.containing(pos).equals(chunk));
            for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
                for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                    for (int y = box.minY(); y <= box.maxY(); y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (expected.at(pos) > own.at(pos)) {
                            crossing++;
                        }
                    }
                }
            }
        }
        String where = lamp + " at " + placement.site().origin().toShortString() + " (lamp chunk "
                + placement.lampChunk() + ", neighbour " + placement.neighbour() + ", " + sent + ", held " + held + ")";
        if (crossing == 0) {
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
        ChunkHolder holder = holder(helper, level, held);
        if (holder.getTickingChunk() != null) {
            throw failure(helper, "%s: the held chunk ticks, so the case proves nothing", where);
        }
        int[] after = storedLight(level, held, placement);
        int changed = 0;
        for (int i = 0; i < after.length; i++) {
            if (after[i] != before[i]) {
                changed++;
            }
        }
        if (sent == Sent.NEIGHBOUR && changed == 0) {
            throw failure(helper, "%s: the draw changed no light in the held chunk, so the case proves nothing", where);
        }
        if (changed > 0 && !holder.hasChangesToBroadcast()) {
            throw failure(
                    helper,
                    "%s: the block light stored in the held chunk changed in %d cells after it was sent, and nothing marks it for a light update, so its clients keep the old light",
                    where,
                    changed);
        }
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aSeaLanternDrawnAfterItsNeighbourWentOutLeavesTheNeighbourMarkedForALightUpdate(GameTestHelper helper) {
        run(helper, Lamp.SEA_LANTERN, Sent.NEIGHBOUR);
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aSeaLanternAtABorderLeavesTheLightRightWhenTheNeighbourIsGeneratedAfterIt(GameTestHelper helper) {
        run(helper, Lamp.SEA_LANTERN, Sent.LAMP_CHUNK);
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aKellsLavaDrawnAfterItsNeighbourWentOutLeavesTheNeighbourMarkedForALightUpdate(GameTestHelper helper) {
        run(helper, Lamp.KELLS_LAVA, Sent.NEIGHBOUR);
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aKellsLavaAtABorderLeavesTheLightRightWhenTheNeighbourIsGeneratedAfterIt(GameTestHelper helper) {
        run(helper, Lamp.KELLS_LAVA, Sent.LAMP_CHUNK);
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aShaftCandleDrawnAfterItsNeighbourWentOutLeavesTheNeighbourMarkedForALightUpdate(
            GameTestHelper helper) {
        run(helper, Lamp.CANDLE, Sent.NEIGHBOUR);
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aShaftCandleAtABorderLeavesTheLightRightWhenTheNeighbourIsGeneratedAfterIt(GameTestHelper helper) {
        run(helper, Lamp.CANDLE, Sent.LAMP_CHUNK);
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theFamousWrecksLanternDrawnAfterItsNeighbourWentOutLeavesTheNeighbourMarkedForALightUpdate(
            GameTestHelper helper) {
        run(helper, Lamp.FAMOUS_LANTERN, Sent.NEIGHBOUR);
    }

    @GameTest(dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void theFamousWrecksLanternAtABorderLeavesTheLightRightWhenTheNeighbourIsGeneratedAfterIt(
            GameTestHelper helper) {
        run(helper, Lamp.FAMOUS_LANTERN, Sent.LAMP_CHUNK);
    }
}
