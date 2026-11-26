package io.github.pkeppeler.deepcharter.layer.mixin;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.List;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a chunk's queued tasks in one list, so they run in the order they were queued (#1448, ADR 0105). The worldgen and
 * light dispatchers share each chunk holder's queue level, so the light dispatcher's resort can read the level the worldgen
 * one has just set and move nothing: vanilla then leaves the chunk's earlier light tasks at the old level and queues later
 * ones at the new level, where a higher priority runs them first. {@code LightSync}'s ring hold relies on that order.
 *
 * <p>Targets, both at their head: {@code ChunkTaskPriorityQueue.resortChunkTasks(int, ChunkPos, int)}, cancelled, finds
 * the chunk's list wherever it is instead of at the old level it is told; {@code ChunkTaskPriorityQueue.submit(Runnable,
 * long, int)} first moves a list the chunk has at another level to the level it is given, then lets vanilla append. The
 * dispatcher calls both, and {@code pop} and {@code release}, on its one executor thread, so no lock is needed.
 */
@Mixin(ChunkTaskPriorityQueue.class)
public abstract class ChunkTaskPriorityQueueMixin {
    @Shadow
    @Final
    private List<Long2ObjectLinkedOpenHashMap<List<Runnable>>> queuesPerPriority;

    @Shadow
    private volatile int topPriorityQueueIndex;

    @Inject(method = "submit(Ljava/lang/Runnable;JI)V", at = @At("HEAD"))
    private void deepcharter$joinTheChunksList(Runnable task, long pos, int level, CallbackInfo ci) {
        if (level < 0 || level >= queuesPerPriority.size()) {
            return; // vanilla's own path, out of range as vanilla is
        }
        if (queuesPerPriority.get(level).containsKey(pos)) {
            return; // the chunk's one list is at this level already: the common case, no scan
        }
        int at = deepcharter$levelOf(pos);
        if (at >= 0 && at != level) {
            deepcharter$move(pos, at, level);
        }
    }

    @Inject(method = "resortChunkTasks(ILnet/minecraft/world/level/ChunkPos;I)V", at = @At("HEAD"), cancellable = true)
    private void deepcharter$resortWhereTheListIs(int oldLevel, ChunkPos pos, int newLevel, CallbackInfo ci) {
        if (newLevel < 0 || newLevel >= queuesPerPriority.size()) {
            return; // vanilla's own path, out of range as vanilla is
        }
        ci.cancel();
        long packed = pos.pack();
        int at = deepcharter$levelOf(packed);
        if (at >= 0) {
            deepcharter$move(packed, at, newLevel);
        }
    }

    /**
     * The level holding the chunk's list, or -1. A scan of every level's map rather than an index of our own: an index
     * would have to follow {@code pop} and {@code release} too, and a map with nothing in it costs one field read.
     */
    @Unique
    private int deepcharter$levelOf(long pos) {
        for (int i = 0; i < queuesPerPriority.size(); i++) {
            Long2ObjectLinkedOpenHashMap<List<Runnable>> queue = queuesPerPriority.get(i);
            if (!queue.isEmpty() && queue.containsKey(pos)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Vanilla's resort, from the level the list is at: the list goes to the end of the new level's order, and an empty one
     * is dropped. The chunk has no list at {@code to} (one list per chunk, and {@code from != to}), so nothing is merged.
     */
    @Unique
    private void deepcharter$move(long pos, int from, int to) {
        @Nullable List<Runnable> tasks = queuesPerPriority.get(from).remove(pos);
        int top = topPriorityQueueIndex; // one write: other threads read it through hasWork()
        while (top < queuesPerPriority.size() && queuesPerPriority.get(top).isEmpty()) {
            top++;
        }
        if (tasks != null && !tasks.isEmpty()) {
            queuesPerPriority
                    .get(to)
                    .computeIfAbsent(pos, p -> Lists.newArrayList())
                    .addAll(tasks);
            top = Math.min(top, to);
        }
        topPriorityQueueIndex = top;
    }
}
