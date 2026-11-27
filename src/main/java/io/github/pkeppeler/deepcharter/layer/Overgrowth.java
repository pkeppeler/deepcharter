package io.github.pkeppeler.deepcharter.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Overgrowth (#974, docs/design/mechanics.md): a bore left unvisited grows over with {@link LayerBlocks#OVERGROWTH}, in
 * the biomes of the tag {@link #HOLLOWS} only (the zones of layer 3, #972).
 *
 * <p>A bore is a cell that a pod drill or a hand removed from natural rock or from growth. Each chunk keeps its bore
 * cells and the game time of its last visit in the attachment {@link #BORES}, written only for a cell in a hollows
 * biome, so the world pays nothing where the tag is empty. A visit is a player (not a spectator) within
 * {@link OvergrowthTuning#visitRadius} blocks of a bore cell of the chunk; a pod counts through its pilot, which is how
 * a drill is driven. Past {@link OvergrowthTuning#delayTicks} without a visit, each pass
 * ({@link OvergrowthTuning#passTicks}) fills up to {@link OvergrowthTuning#cellsPerPass} of the chunk's bore cells.
 * Only chunks that are loaded and ticking grow, as random ticks do.
 *
 * <p>Growth only ever fills an air cell, so a placed block is never replaced and never deleted (SPEC section 10). A
 * cell grows only when it touches natural rock or growth, so it closes from the walls inward, and never when a lining
 * block ({@link #LINING}) is within {@link OvergrowthTuning#holdReach} blocks (a lined shaft stays open), when the
 * biome is not in the tag, or when an entity stands in it (a pod or a player is never walled into the cell it fills).
 */
public final class Overgrowth {
    private static final int VERSION = 1;

    private static final long SPILL_SALT = 5L;

    /** The biomes whose bores grow over. The zones of layer 3 (#972); the test data adds a biome of its own. */
    public static final TagKey<Biome> HOLLOWS = TagKey.create(Registries.BIOME, id("fungal_hollows"));

    /** Blocks that hold a bore open: a lined shaft stays open for good. */
    public static final TagKey<Block> LINING = TagKey.create(Registries.BLOCK, id("lining"));

    /**
     * The pending bore cells of a chunk (packed block positions: cut, and not yet grown over) and the game time of the
     * last visit. A mark is dropped when growth fills its cell, or when something that is neither rock nor growth does;
     * a cut of the growth marks it again, so a bore that is cut open grows over again. A chunk with no pending cell is
     * not tracked.
     */
    public record Bores(long lastVisit, Set<Long> cells) {
        static final Bores EMPTY = new Bores(0L, Set.of());
        public static final MapCodec<Bores> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Codec.LONG.fieldOf("last_visit").forGetter(Bores::lastVisit),
                        Codec.LONG
                                .listOf()
                                .<Set<Long>>xmap(Set::copyOf, List::copyOf)
                                .fieldOf("cells")
                                .forGetter(Bores::cells))
                .apply(instance, Bores::new));
    }

    /** Per chunk, persistent, never sent to a client. */
    public static final AttachmentType<Versioned<Bores>> BORES = AttachmentRegistry.create(
            id("bores"),
            builder -> builder.persistent(Versioned.codec(VERSION, Bores.BODY))
                    .initializer(() -> Versioned.of(Bores.EMPTY)));

    /** The chunks with bore cells that are loaded, by dimension; the growth pass walks these and nothing else. */
    private static final Map<ResourceKey<Level>, Set<ChunkPos>> TRACKED = new HashMap<>();

    private Overgrowth() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
    }

    /**
     * The biome stored for the cell: {@code Level.getBiome} blends neighbouring cells at an edge, which would let
     * growth leak a block out of the hollows.
     */
    private static Holder<Biome> stored(ServerLevel level, BlockPos pos) {
        return level.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
    }

    public static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            if (chunk.hasAttached(BORES)) {
                track(level, chunk.getPos());
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> untrack(level, chunk.getPos()));
        // A player who is not in the level is no visit and no entity: growth may close round the place they logged out,
        // so a leave counts as a visit and a join frees the cells they stand in.
        ServerPlayerEvents.LEAVE.register(Overgrowth::leave);
        ServerPlayerEvents.JOIN.register(Overgrowth::freePlayer);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> TRACKED.clear());
        ServerTickEvents.END_LEVEL_TICK.register(Overgrowth::tick);
        // A hand cut is a bore too (rock below layer 1 is out of
        // reach of hands, so this is layer 1 and the growth itself).
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel server && cutsBore(state)) {
                markBore(server, pos);
            }
        });
    }

    /**
     * True for what a bore is cut from: natural rock, the waste rock of the surface (dirt), Company rock (which is not
     * in {@code natural_rock}: the drill bites it through {@code undiggable}) and growth itself. Breach crust is not
     * cut by this path ({@code BreachService}).
     */
    public static boolean cutsBore(BlockState state) {
        return isWall(state) || state.is(LayerBlocks.OVERGROWTH);
    }

    private static boolean isWall(BlockState state) {
        return state.is(HazardBlocks.NATURAL_ROCK)
                || state.is(SlagBrick.WASTE_ROCK)
                || state.is(HazardBlocks.UNDIGGABLE);
    }

    /**
     * A player joins inside growth (it closed round the place they logged out): the cells of their feet and head are
     * cleared and stay pending.
     */
    public static void freePlayer(ServerPlayer player) {
        ServerLevel level = player.level();
        for (BlockPos cell :
                List.of(player.blockPosition(), player.blockPosition().above())) {
            if (level.getBlockState(cell).is(LayerBlocks.OVERGROWTH)) {
                level.destroyBlock(cell, false);
                markBore(level, cell);
            }
        }
    }

    /** A player leaves: the bores within reach of them count as visited now. */
    public static void leave(ServerPlayer player) {
        ServerLevel level = player.level();
        Set<ChunkPos> chunks = TRACKED.get(level.dimension());
        if (chunks == null) {
            return;
        }
        long now = level.getGameTime();
        for (ChunkPos at : List.copyOf(chunks)) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(at.getMinBlockX() >> 4, at.getMinBlockZ() >> 4);
            if (chunk == null) {
                continue;
            }
            Optional<Bores> read = Versioned.readable(chunk, BORES);
            if (read.isPresent() && near(player, read.get())) {
                chunk.setAttached(BORES, Versioned.of(new Bores(now, read.get().cells())));
            }
        }
    }

    /**
     * Records that {@code pos} was cut out of rock or growth, which is also a visit to its chunk. Does nothing outside
     * the hollows biomes, or where the chunk's saved bores are of a version this build cannot read (they are never
     * overwritten). Call it after the block is gone.
     */
    public static void markBore(ServerLevel level, BlockPos pos) {
        if (!stored(level, pos).is(HOLLOWS)) {
            return;
        }
        LevelChunk chunk = level.getChunkAt(pos);
        Optional<Bores> read = Versioned.readable(chunk, BORES);
        if (read.isEmpty()) {
            return;
        }
        Set<Long> cells = new HashSet<>(read.get().cells());
        cells.add(pos.asLong());
        chunk.setAttached(BORES, Versioned.of(new Bores(level.getGameTime(), Set.copyOf(cells))));
        track(level, chunk.getPos());
    }

    private static void track(ServerLevel level, ChunkPos chunk) {
        TRACKED.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(chunk);
    }

    private static void untrack(ServerLevel level, ChunkPos chunk) {
        Set<ChunkPos> chunks = TRACKED.get(level.dimension());
        if (chunks != null) {
            chunks.remove(chunk);
            if (chunks.isEmpty()) {
                TRACKED.remove(level.dimension());
            }
        }
    }

    private static void tick(ServerLevel level) {
        Set<ChunkPos> chunks = TRACKED.get(level.dimension());
        if (chunks == null) {
            return;
        }
        long now = level.getGameTime();
        int pass = OvergrowthTuning.DEFAULT.passTicks();
        for (ChunkPos at : List.copyOf(chunks)) {
            // Each chunk has its own tick in the pass, so a level of bores does not do all its work on one tick.
            if (Math.floorMod(now - at.hashCode(), pass) != 0) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(at.getMinBlockX() >> 4, at.getMinBlockZ() >> 4);
            if (chunk == null) {
                untrack(level, at);
            } else if (Versioned.readable(chunk, BORES)
                    .filter(bores -> bores.cells().isEmpty())
                    .isPresent()) {
                untrack(level, at);
            } else if (level.isPositionEntityTicking(at.getWorldPosition())) {
                stampVisit(level, chunk, now);
                grow(level, chunk, now);
            }
        }
    }

    /**
     * Writes {@code now} as the chunk's last visit when a player is within reach of one of its bore cells; writes at
     * most every {@link OvergrowthTuning#visitStampTicks}.
     */
    public static void stampVisit(ServerLevel level, LevelChunk chunk, long now) {
        Optional<Bores> read = Versioned.readable(chunk, BORES);
        if (read.isEmpty() || now - read.get().lastVisit() < OvergrowthTuning.DEFAULT.visitStampTicks()) {
            return;
        }
        if (visited(level, read.get())) {
            chunk.setAttached(BORES, Versioned.of(new Bores(now, read.get().cells())));
        }
    }

    /** Creative players visit; spectators, who fly through rock, do not. */
    private static boolean visited(ServerLevel level, Bores bores) {
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && near(player, bores)) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(ServerPlayer player, Bores bores) {
        double reach = OvergrowthTuning.DEFAULT.visitRadius();
        for (long cell : bores.cells()) {
            if (BlockPos.of(cell).distToCenterSqr(player.position()) <= reach * reach) {
                return true;
            }
        }
        return false;
    }

    /**
     * One growth pass over a chunk at game time {@code now}: when the chunk has gone unvisited for the delay, weighs
     * the {@link OvergrowthTuning#maxLooks} pending cells with the smallest scrambled key (the key changes each pass,
     * so the film spreads unevenly and every cell is weighed in time) and fills up to the pass's share of those that
     * are free to grow. A filled cell, and a cell that a placed block has taken over, stop being pending.
     */
    public static void grow(ServerLevel level, LevelChunk chunk, long now) {
        Optional<Bores> read = Versioned.readable(chunk, BORES);
        if (read.isEmpty() || now - read.get().lastVisit() < OvergrowthTuning.DEFAULT.delayTicks()) {
            return;
        }
        Bores bores = read.get();
        long pass = now / OvergrowthTuning.DEFAULT.passTicks();
        LoadedBlocks blocks = new LoadedBlocks(level);
        Set<Long> done = new HashSet<>();
        int grown = 0;
        for (long cell : weighed(bores.cells(), pass)) {
            BlockPos pos = BlockPos.of(cell);
            BlockState state = blocks.getBlockState(pos);
            if (state.isAir() || state.is(LayerBlocks.OVERGROWTH_VENEER)) {
                if (!freeToGrow(level, blocks, pos)) {
                    continue;
                }
                if (grown < OvergrowthTuning.DEFAULT.cellsPerPass()) {
                    level.setBlock(pos, LayerBlocks.OVERGROWTH.defaultBlockState(), Block.UPDATE_ALL);
                    grown++;
                    done.add(cell);
                    spill(level, blocks, pos);
                } else {
                    film(level, blocks, pos, pass, liningNear(blocks, pos, OvergrowthTuning.DEFAULT.holdReach()));
                }
            } else if (state.is(LayerBlocks.OVERGROWTH)
                    || (!isWall(state) && state.getFluidState().isEmpty())) {
                done.add(cell);
            }
        }
        if (!done.isEmpty()) {
            Set<Long> pending = new HashSet<>(bores.cells());
            pending.removeAll(done);
            chunk.setAttached(BORES, Versioned.of(new Bores(bores.lastVisit(), Set.copyOf(pending))));
        }
    }

    /**
     * Lays the moss film in {@code pos}, an air cell, on the faces of the rock round it, leaving
     * {@link OvergrowthTuning#filmBarePercent} of the faces bare so the film is ragged. It does nothing where growth
     * would not: outside the hollows, within reach of one of {@code lining} (the lining blocks near the cell), or where
     * a chunk is not loaded. {@code salt} varies which faces.
     */
    private static void film(ServerLevel level, LoadedBlocks blocks, BlockPos pos, long salt, List<BlockPos> lining) {
        if (!blocks.getBlockState(pos).isAir()
                || !blocks.canChange(pos)
                || !stored(level, pos).is(HOLLOWS)) {
            return;
        }
        int reach = OvergrowthTuning.DEFAULT.holdReach();
        for (BlockPos block : lining) {
            if (Math.abs(block.getX() - pos.getX()) <= reach
                    && Math.abs(block.getY() - pos.getY()) <= reach
                    && Math.abs(block.getZ() - pos.getZ()) <= reach) {
                return;
            }
        }
        BlockState film = LayerBlocks.OVERGROWTH_VENEER.defaultBlockState();
        boolean any = false;
        for (Direction side : Direction.values()) {
            if (isWall(blocks.getBlockState(pos.relative(side)))
                    && Math.floorMod(scramble(pos.asLong() * 6 + side.ordinal(), salt), 100)
                            >= OvergrowthTuning.DEFAULT.filmBarePercent()) {
                film = film.setValue(MultifaceBlock.getFaceProperty(side), true);
                any = true;
            }
        }
        if (any) {
            level.setBlock(pos, film, Block.UPDATE_ALL);
        }
    }

    /**
     * The lining blocks within {@code reach} of {@code centre},
     * read once for a cell or for all the cells a spill reaches.
     */
    private static List<BlockPos> liningNear(LoadedBlocks blocks, BlockPos centre, int reach) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos near :
                BlockPos.betweenClosed(centre.offset(-reach, -reach, -reach), centre.offset(reach, reach, reach))) {
            if (blocks.getBlockState(near).is(LINING)) {
                found.add(near.immutable());
            }
        }
        return found;
    }

    /**
     * The film spills out round a cell that has just grown: each air cell within {@link OvergrowthTuning#filmReach} of
     * it takes a film with a chance that falls with distance, fixed by the cell, so the edge round a closed mouth is
     * ragged and the same every time.
     */
    private static void spill(ServerLevel level, LoadedBlocks blocks, BlockPos grown) {
        OvergrowthTuning tuning = OvergrowthTuning.DEFAULT;
        int spill = tuning.filmReach();
        List<BlockPos> lining = liningNear(blocks, grown, spill + tuning.holdReach());
        for (BlockPos near :
                BlockPos.betweenClosed(grown.offset(-spill, -spill, -spill), grown.offset(spill, spill, spill))) {
            int reach = Math.max(
                    Math.abs(near.getX() - grown.getX()),
                    Math.max(Math.abs(near.getY() - grown.getY()), Math.abs(near.getZ() - grown.getZ())));
            int chance = reach <= 1 ? tuning.spillNearPercent() : tuning.spillFarPercent();
            if (reach > 0 && Math.floorMod(scramble(near.asLong(), SPILL_SALT), 100) < chance) {
                film(level, blocks, near.immutable(), SPILL_SALT, lining);
            }
        }
    }

    /**
     * True while something holds the film at {@code pos} at game time {@code now}: the cell is a pending bore cell of a
     * chunk that has gone unvisited for the delay (the film ahead of the fill), or growth stands within
     * {@link OvergrowthTuning#filmReach} of it. A visit, or a cut of the growth, lets go of it. A film never lets go
     * because of a chunk it cannot see: an unloaded cell in reach counts as held.
     */
    public static boolean anchored(ServerLevel level, BlockPos pos, long now) {
        boolean pending = Versioned.readable(level.getChunkAt(pos), BORES)
                .filter(bores -> bores.cells().contains(pos.asLong())
                        && now - bores.lastVisit() >= OvergrowthTuning.DEFAULT.delayTicks())
                .isPresent();
        if (pending) {
            return true;
        }
        int reach = OvergrowthTuning.DEFAULT.filmReach();
        for (BlockPos near :
                BlockPos.betweenClosed(pos.offset(-reach, -reach, -reach), pos.offset(reach, reach, reach))) {
            if (!LoadedBlocks.isLoaded(level, near) || level.getBlockState(near).is(LayerBlocks.OVERGROWTH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@link OvergrowthTuning#maxLooks} cells with the smallest key this pass, smallest first, in one scan with no
     * sort of the whole set.
     */
    private static List<Long> weighed(Set<Long> cells, long pass) {
        PriorityQueue<long[]> largest = new PriorityQueue<>(
                Comparator.comparingLong((long[] pair) -> pair[0]).reversed());
        for (long cell : cells) {
            long key = scramble(cell, pass);
            if (largest.size() < OvergrowthTuning.DEFAULT.maxLooks()) {
                largest.add(new long[] {key, cell});
            } else if (key < largest.peek()[0]) {
                largest.poll();
                largest.add(new long[] {key, cell});
            }
        }
        List<long[]> picked = new ArrayList<>(largest);
        picked.sort(Comparator.comparingLong(pair -> pair[0]));
        return picked.stream().map(pair -> pair[1]).toList();
    }

    private static long scramble(long cell, long pass) {
        long z = cell + pass * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static boolean freeToGrow(ServerLevel level, LoadedBlocks blocks, BlockPos pos) {
        return blocks.canChange(pos)
                && stored(level, pos).is(HOLLOWS)
                && touchesRockOrGrowth(blocks, pos)
                && !heldOpen(blocks, pos)
                && !occupied(level, pos);
    }

    private static boolean touchesRockOrGrowth(LoadedBlocks blocks, BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockState next = blocks.getBlockState(pos.relative(side));
            if (isWall(next) || next.is(LayerBlocks.OVERGROWTH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An unloaded neighbouring chunk reads as air here, so a lining block across a chunk border that is not loaded does
     * not hold; {@code canChange} has already refused a cell on a loaded border's unloaded side.
     */
    private static boolean heldOpen(LoadedBlocks blocks, BlockPos pos) {
        int reach = OvergrowthTuning.DEFAULT.holdReach();
        for (BlockPos near :
                BlockPos.betweenClosed(pos.offset(-reach, -reach, -reach), pos.offset(reach, reach, reach))) {
            if (blocks.getBlockState(near).is(LINING)) {
                return true;
            }
        }
        return false;
    }

    private static boolean occupied(ServerLevel level, BlockPos pos) {
        // entity-query: the cell is in a loaded, ticking chunk, and canChange has refused a cell beside an unloaded
        // one; a miss only lets a cell grow
        return !level.getEntities(
                        (Entity) null,
                        new AABB(pos),
                        entity -> !entity.isSpectator() && !(entity instanceof ItemEntity))
                .isEmpty();
    }
}
