package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.BlockingLoads;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells {@link BlockingLoads} each time the server thread waits for a chunk: {@code getChunk} on a chunk that is not in its cache
 * schedules the load and then blocks in {@code managedBlock} until the workers finish it. Inert unless a test armed it.
 */
@Mixin(value = ServerChunkCache.class, remap = false)
public abstract class ServerChunkCacheBlockingLoadMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(
            method =
                    "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"))
    private void deepcharter$recordBlockingLoad(
            int chunkX, int chunkZ, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> result) {
        BlockingLoads.record(level.dimension(), chunkX, chunkZ);
    }
}
