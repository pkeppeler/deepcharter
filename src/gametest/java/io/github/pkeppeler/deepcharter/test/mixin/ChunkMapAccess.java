package io.github.pkeppeler.deepcharter.test.mixin;

import java.util.Queue;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * For {@link ChunkMapUnloadMixin} and {@link ChunkMapSaveMixin}: the unload queue, its runner, and one chunk save; the light report reads
 * a chunk's holder.
 */
@Mixin(value = ChunkMap.class, remap = false)
public interface ChunkMapAccess {
    @Accessor("unloadQueue")
    Queue<Runnable> deepcharter$unloadQueue();

    @Invoker("save")
    boolean deepcharter$save(ChunkAccess chunk);

    @Invoker("processUnloads")
    void deepcharter$processUnloads(BooleanSupplier hasMoreTime);

    @Invoker("getVisibleChunkIfPresent")
    @Nullable
    ChunkHolder deepcharter$visibleHolder(long chunk);
}
