package io.github.pkeppeler.deepcharter.test.mixin;

import java.util.Queue;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@link ChunkMapUnloadMixin}'s test reaches the unload queue and the step that runs it. */
@Mixin(value = ChunkMap.class, remap = false)
public interface ChunkMapAccess {
    @Accessor("unloadQueue")
    Queue<Runnable> deepcharter$unloadQueue();

    @Invoker("processUnloads")
    void deepcharter$processUnloads(BooleanSupplier hasMoreTime);
}
