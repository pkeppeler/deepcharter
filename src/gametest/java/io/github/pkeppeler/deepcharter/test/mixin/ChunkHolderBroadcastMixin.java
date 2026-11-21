package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.LightBroadcasts;
import java.util.BitSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link LightBroadcasts} which block light sections a holder is about to send while its chunk does not tick, for
 * {@code StructureLightBorderTest}. Inert unless the test watches the chunk.
 */
@Mixin(value = ChunkHolder.class, remap = false)
public abstract class ChunkHolderBroadcastMixin {
    @Shadow
    @Final
    private BitSet blockChangedLightSectionFilter;

    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void deepcharter$recordLightBroadcast(LevelChunk chunk, CallbackInfo ci) {
        if (((ChunkHolder) (Object) this).getTickingChunk() == null) {
            LightBroadcasts.record(chunk.getPos(), blockChangedLightSectionFilter);
        }
    }
}
