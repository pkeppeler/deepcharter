package io.github.pkeppeler.deepcharter.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.pkeppeler.deepcharter.test.support.BlockingLoads;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * Tells {@link BlockingLoads} each time the server thread has to wait for a chunk: {@code getChunk} on a chunk that is not in its cache
 * schedules the load and then blocks in {@code managedBlock} until it is done. A chunk that is loaded already is done at once, and is not a
 * wait. Inert unless a test armed it.
 */
@Mixin(value = ServerChunkCache.class, remap = false)
public abstract class ServerChunkCacheBlockingLoadMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @WrapOperation(
            method =
                    "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"))
    private void deepcharter$recordBlockingLoad(
            @Coerce Object executor,
            BooleanSupplier isDone,
            Operation<Void> original,
            int chunkX,
            int chunkZ,
            ChunkStatus status,
            boolean load) {
        if (!isDone.getAsBoolean()) {
            BlockingLoads.record(level.dimension(), chunkX, chunkZ);
        }
        original.call(executor, isDone);
    }
}
