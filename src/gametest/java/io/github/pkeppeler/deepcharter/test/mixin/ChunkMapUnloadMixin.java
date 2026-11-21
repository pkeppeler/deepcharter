package io.github.pkeppeler.deepcharter.test.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lets a chunk unload finish in the tick it is queued. {@code ChunkMap.processUnloads} runs the queued unload tails (the step that
 * drops a saved chunk from {@code pendingUnloads}) only while the tick has time left, or once more than 2000 are queued. The
 * GameTest server ticks over budget while chunks generate, so the tails of one batch's tall-world chunks were still queued, with
 * their section data, when the next batch began (#807).
 *
 * <p>This gives {@code processUnloads} unlimited time, not only the unload queue: the argument is also passed on to
 * {@code saveChunksEagerly}, so eager saves lose their time cap every tick too. Deliberately broad: it is what was measured.
 */
@Mixin(value = ChunkMap.class, remap = false)
public abstract class ChunkMapUnloadMixin {
    @ModifyVariable(method = "processUnloads", at = @At("HEAD"), argsOnly = true)
    private BooleanSupplier deepcharter$alwaysHasTime(BooleanSupplier hasMoreTime) {
        return () -> true;
    }
}
