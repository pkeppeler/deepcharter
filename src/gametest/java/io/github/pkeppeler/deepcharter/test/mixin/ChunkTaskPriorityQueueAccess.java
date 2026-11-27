package io.github.pkeppeler.deepcharter.test.mixin;

import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** For {@code ChunkTaskQueueOrderTest}: the queue's protected submit and resort, which its dispatcher calls. */
@Mixin(value = ChunkTaskPriorityQueue.class, remap = false)
public interface ChunkTaskPriorityQueueAccess {
    @Invoker("submit")
    void deepcharter$submit(Runnable task, long pos, int level);

    @Invoker("resortChunkTasks")
    void deepcharter$resortChunkTasks(int oldLevel, ChunkPos pos, int newLevel);
}
