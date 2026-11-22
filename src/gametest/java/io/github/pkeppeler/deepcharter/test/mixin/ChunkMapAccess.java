package io.github.pkeppeler.deepcharter.test.mixin;

import java.util.Queue;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The tests of {@link ChunkMapUnloadMixin} and {@link ChunkMapSaveMixin} reach the unload queue, the step that runs it and the save of one chunk. */
@Mixin(value = ChunkMap.class, remap = false)
public interface ChunkMapAccess {
    @Accessor("unloadQueue")
    Queue<Runnable> deepcharter$unloadQueue();

    @Invoker("save")
    boolean deepcharter$save(ChunkAccess chunk);

    @Invoker("processUnloads")
    void deepcharter$processUnloads(BooleanSupplier hasMoreTime);
}
