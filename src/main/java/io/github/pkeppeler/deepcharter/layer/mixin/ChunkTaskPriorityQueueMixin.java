package io.github.pkeppeler.deepcharter.layer.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
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
 * Keeps a chunk's queued tasks in one list across all levels, so they run in the order they were queued, which
 * {@code LightSync}'s ring hold relies on. The race this closes, the targets and why a scan: ADR 0105 (#1448).
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
        if (!deepcharter$inRange(level)) {
            return;
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
        if (!deepcharter$inRange(newLevel)) {
            return;
        }
        ci.cancel();
        long packed = pos.pack();
        int at = deepcharter$levelOf(packed);
        if (at >= 0) {
            deepcharter$move(packed, at, newLevel);
        }
    }

    /** Out of range, vanilla's own path runs and fails as vanilla does; a holder never asks for such a level. */
    @Unique
    private boolean deepcharter$inRange(int level) {
        return level >= 0 && level < queuesPerPriority.size();
    }

    /** The level holding the chunk's list, or -1. */
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

    /** Vanilla's resort, from the level the list is at: it goes to the end of the new level's order, an empty one dropped. */
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
                    .computeIfAbsent(pos, p -> new ArrayList<>())
                    .addAll(tasks);
            top = Math.min(top, to);
        }
        topPriorityQueueIndex = top;
    }
}
