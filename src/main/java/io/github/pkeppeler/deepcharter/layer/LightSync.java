package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Light is computed off the server thread, and vanilla drops the light update of a chunk that is not yet ticking (issue 427), so a client
 * sent a chunk while light is pending keeps the old light; call this after the last block placed in a chunk a player may be sent.
 *
 * <p>Vanilla tells the players who track a chunk that it is ready to send once, when the chunk starts ticking. A player who comes into
 * view of a chunk while it is held is not sent it then, and nothing tells that player when the hold ends (issue 524: the client never
 * received the chunk). This class watches each hold it makes and, when the chunk is sendable again, marks it pending for every player who
 * tracks it and has not had it since the hold began. A hold that outlasts {@link #TIMEOUT_TICKS} is logged as an error naming the chunk.
 */
public final class LightSync {
    /** Server ticks a hold may last before it is reported: the light of one chunk takes a few ticks, so 30 s is a stuck light engine. */
    static final int TIMEOUT_TICKS = 600;

    /** A chunk whose sending is held, and the players who tracked it throughout the hold (they were or will be sent it by vanilla). */
    private record Hold(ServerLevel level, ChunkPos chunk, int startTick, Set<UUID> trackedThroughout) {}

    /** Server thread only. */
    private static final List<Hold> HOLDS = new ArrayList<>();

    private LightSync() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(LightSync::sweep);
        ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> HOLDS.removeIf(hold -> hold.level.getServer() == server));
    }

    /** As {@link #holdSendUntilLit(ServerLevel, ChunkPos)}, for every chunk the box touches. */
    public static void holdSendUntilLit(ServerLevel level, BlockPos min, BlockPos max) {
        ChunkPos.rangeClosed(ChunkPos.containing(min), ChunkPos.containing(max))
                .forEach(chunk -> holdSendUntilLit(level, chunk));
    }

    /**
     * Holds the sending of the chunk, and of each of its eight neighbours that has a loaded holder (light spreads at most 15 blocks, so
     * one ring is enough), until the light tasks queued so far are done. It is vanilla's {@code ChunkMap.waitForLightBeforeSending},
     * and the hold is released to every player who comes into view while it lasts.
     */
    public static void holdSendUntilLit(ServerLevel level, ChunkPos chunk) {
        List<ChunkPos> sendable = new ArrayList<>();
        ChunkPos.rangeClosed(chunk, 1)
                .filter(near -> level.getChunkSource().chunkMap.getChunkToSend(near.pack()) != null)
                .forEach(sendable::add);
        level.getChunkSource().chunkMap.waitForLightBeforeSending(chunk, 0);
        for (ChunkPos held : sendable) {
            if (HOLDS.stream().noneMatch(hold -> hold.level == level && hold.chunk.equals(held))) {
                HOLDS.add(new Hold(level, held, level.getServer().getTickCount(), trackers(level, held)));
            }
        }
    }

    private static Set<UUID> trackers(ServerLevel level, ChunkPos chunk) {
        Set<UUID> tracking = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (player.getChunkTrackingView().contains(chunk)) {
                tracking.add(player.getUUID());
            }
        }
        return tracking;
    }

    private static void sweep(MinecraftServer server) {
        Iterator<Hold> holds = HOLDS.iterator();
        while (holds.hasNext()) {
            Hold hold = holds.next();
            if (hold.level.getServer() != server) {
                continue;
            }
            if (hold.level.getChunkSource().getChunkNow(hold.chunk.x(), hold.chunk.z()) == null) {
                holds.remove(); // unloaded: the next load sends it the vanilla way
                continue;
            }
            LevelChunk sendable = hold.level.getChunkSource().chunkMap.getChunkToSend(hold.chunk.pack());
            if (sendable != null) {
                markPendingForLateTrackers(hold, sendable);
                holds.remove();
            } else {
                if (server.getTickCount() - hold.startTick == TIMEOUT_TICKS) {
                    DeepCharter.LOGGER.error(
                            "The sending of chunk {} in {} has been held for {} ticks and the light has not released it",
                            hold.chunk,
                            hold.level.dimension().identifier(),
                            TIMEOUT_TICKS);
                }
                hold.trackedThroughout.retainAll(trackers(hold.level, hold.chunk));
            }
        }
    }

    private static void markPendingForLateTrackers(Hold hold, LevelChunk chunk) {
        for (ServerPlayer player : hold.level.players()) {
            if (player.getChunkTrackingView().contains(hold.chunk)
                    && !hold.trackedThroughout.contains(player.getUUID())) {
                player.connection.chunkSender.markChunkPendingToSend(chunk);
            }
        }
    }
}
