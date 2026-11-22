package io.github.pkeppeler.deepcharter.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * Overgrowth (#974, docs/design/mechanics.md): a bore left unvisited grows over with {@link LayerBlocks#OVERGROWTH}, in the biomes of the
 * tag {@link #HOLLOWS} only (empty in shipped data until the layer that has them, #972).
 *
 * <p>A bore is a cell that a pod drill or a hand removed from natural rock or from growth. Each chunk keeps its bore cells and the game
 * time of its last visit in the attachment {@link #BORES}, written only for a cell in a hollows biome, so the world pays nothing where
 * the tag is empty. A visit is a player (not a spectator) within {@link OvergrowthTuning#visitRadius} blocks of a bore cell of the chunk;
 * a pod counts through its pilot, which is how a drill is driven. Past {@link OvergrowthTuning#delayTicks} without a visit, each pass
 * ({@link OvergrowthTuning#passTicks}) fills up to {@link OvergrowthTuning#cellsPerPass} of the chunk's bore cells. Only chunks that are
 * loaded and ticking grow, as random ticks do.
 *
 * <p>Growth only ever fills an air cell, so a placed block is never replaced and never deleted (SPEC section 10). A cell grows only
 * when it touches natural rock or growth, so it closes from the walls inward, and never when a lining block ({@link #LINING}) is within
 * {@link OvergrowthTuning#holdReach} blocks (a lined shaft stays open), when the biome is not in the tag, or when an entity stands in it
 * (a pod or a player is never walled into the cell it fills).
 */
public final class Overgrowth {
    private static final int VERSION = 1;

    /** The biomes whose bores grow over. Empty in shipped data: the layer that has the hollows fills it (#972). */
    public static final TagKey<Biome> HOLLOWS = TagKey.create(Registries.BIOME, id("fungal_hollows"));

    /** Blocks that hold a bore open: a lined shaft stays open for good. */
    public static final TagKey<Block> LINING = TagKey.create(Registries.BLOCK, id("lining"));

    /**
     * The bore cells of a chunk (packed block positions) and the game time of the last visit. Cells stay marked while growth fills them, so a
     * bore that is cut open again grows over again; a mark is dropped when something that is neither natural rock nor growth fills its cell.
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

    private static OvergrowthTuning tuning() {
        return OvergrowthTuning.DEFAULT;
    }

    public static void init() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyGenerated) -> {
            if (chunk.hasAttached(BORES)) {
                track(level, chunk.getPos());
            }
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> untrack(level, chunk.getPos()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> TRACKED.clear());
        ServerTickEvents.END_LEVEL_TICK.register(Overgrowth::tick);
        // A hand cut is a bore too (rock below layer 1 is out of reach of hands, so this is layer 1 and the growth
        // itself).
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (level instanceof ServerLevel server && cutsBore(state)) {
                markBore(server, pos);
            }
        });
    }

    /** True for what a bore is cut from: natural rock (Company rock too) and growth itself. */
    public static boolean cutsBore(BlockState state) {
        return state.is(HazardBlocks.NATURAL_ROCK)
                || state.is(HazardBlocks.UNDIGGABLE)
                || state.is(LayerBlocks.OVERGROWTH);
    }

    /**
     * Records that {@code pos} was cut out of rock or growth, which is also a visit to its chunk. Does nothing outside the hollows biomes, or
     * where the chunk's saved bores are of a version this build cannot read (they are never overwritten). Call it after the block is gone.
     */
    public static void markBore(ServerLevel level, BlockPos pos) {
        if (!level.getBiome(pos).is(HOLLOWS)) {
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
        }
    }

    private static void tick(ServerLevel level) {
        long now = level.getGameTime();
        Set<ChunkPos> chunks = TRACKED.get(level.dimension());
        if (chunks == null || chunks.isEmpty() || now % tuning().passTicks() != 0) {
            return;
        }
        for (ChunkPos at : List.copyOf(chunks)) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(at.getMinBlockX() >> 4, at.getMinBlockZ() >> 4);
            if (chunk == null) {
                chunks.remove(at);
            } else if (level.isPositionEntityTicking(at.getWorldPosition())) {
                stampVisit(level, chunk, now);
                grow(level, chunk, now);
            }
        }
    }

    /** Writes {@code now} as the chunk's last visit when a player is within reach of one of its bore cells; writes at most every {@link OvergrowthTuning#visitStampTicks}. */
    public static void stampVisit(ServerLevel level, LevelChunk chunk, long now) {
        Optional<Bores> read = Versioned.readable(chunk, BORES);
        if (read.isEmpty() || now - read.get().lastVisit() < tuning().visitStampTicks()) {
            return;
        }
        if (visited(level, read.get())) {
            chunk.setAttached(BORES, Versioned.of(new Bores(now, read.get().cells())));
        }
    }

    private static boolean visited(ServerLevel level, Bores bores) {
        double reach = tuning().visitRadius();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            for (long cell : bores.cells()) {
                if (BlockPos.of(cell).distToCenterSqr(player.position()) <= reach * reach) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * One growth pass over a chunk at game time {@code now}: when the chunk has gone unvisited for the delay, fills up to the pass's share of
     * its bore cells that are free to grow, in a scrambled order that changes each pass, so the film spreads unevenly. Marks that a
     * placed block has taken over are dropped as they are met.
     */
    public static void grow(ServerLevel level, LevelChunk chunk, long now) {
        Optional<Bores> read = Versioned.readable(chunk, BORES);
        if (read.isEmpty() || now - read.get().lastVisit() < tuning().delayTicks()) {
            return;
        }
        Bores bores = read.get();
        long pass = now / tuning().passTicks();
        List<Long> order = new ArrayList<>(bores.cells());
        order.sort(Comparator.comparingLong(cell -> scramble(cell, pass)));
        LoadedBlocks blocks = new LoadedBlocks(level);
        Set<Long> placedOver = new HashSet<>();
        int grown = 0;
        int looks = 0;
        for (long cell : order) {
            if (grown >= tuning().cellsPerPass() || looks >= tuning().maxLooks()) {
                break;
            }
            BlockPos pos = BlockPos.of(cell);
            BlockState state = blocks.getBlockState(pos);
            if (state.isAir()) {
                looks++;
                if (freeToGrow(level, blocks, pos)) {
                    level.setBlock(pos, LayerBlocks.OVERGROWTH.defaultBlockState(), Block.UPDATE_ALL);
                    grown++;
                }
            } else if (!state.is(LayerBlocks.OVERGROWTH)
                    && !state.is(HazardBlocks.NATURAL_ROCK)
                    && state.getFluidState().isEmpty()) {
                placedOver.add(cell);
            }
        }
        if (!placedOver.isEmpty()) {
            Set<Long> kept = new HashSet<>(bores.cells());
            kept.removeAll(placedOver);
            chunk.setAttached(BORES, Versioned.of(new Bores(bores.lastVisit(), Set.copyOf(kept))));
        }
    }

    private static long scramble(long cell, long pass) {
        long z = cell + pass * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static boolean freeToGrow(ServerLevel level, LoadedBlocks blocks, BlockPos pos) {
        return blocks.canChange(pos)
                && level.getBiome(pos).is(HOLLOWS)
                && touchesRockOrGrowth(blocks, pos)
                && !heldOpen(blocks, pos)
                && !occupied(level, pos);
    }

    private static boolean touchesRockOrGrowth(LoadedBlocks blocks, BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockState next = blocks.getBlockState(pos.relative(side));
            if (next.is(HazardBlocks.NATURAL_ROCK) || next.is(LayerBlocks.OVERGROWTH)) {
                return true;
            }
        }
        return false;
    }

    private static boolean heldOpen(LoadedBlocks blocks, BlockPos pos) {
        int reach = tuning().holdReach();
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
