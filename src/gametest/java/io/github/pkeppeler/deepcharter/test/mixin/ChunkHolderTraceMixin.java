package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.LightTrace;
import java.util.BitSet;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds {@link LightTrace} what a chunk holder does with a light change: whether {@code sectionLightChanged} marked it, what
 * {@code broadcastChanges} found to send, and who each packet went to. Records only while the trace watches a box. Test code only.
 */
@Mixin(value = ChunkHolder.class, remap = false)
public abstract class ChunkHolderTraceMixin {
    @Shadow
    @SuppressWarnings("UnusedVariable") // read through the shadow
    private BitSet skyChangedLightSectionFilter;

    @Shadow
    @SuppressWarnings("UnusedVariable") // read through the shadow
    private BitSet blockChangedLightSectionFilter;

    private ChunkHolder deepcharter$self() {
        return (ChunkHolder) (Object) this;
    }

    @Inject(method = "sectionLightChanged", at = @At("RETURN"))
    private void deepcharter$traceMark(LightLayer layer, int sectionY, CallbackInfoReturnable<Boolean> cir) {
        if (LightTrace.watching()) {
            LightTrace.record(
                    "SRV",
                    "LIGHT-MARK",
                    deepcharter$self().getPos().x(),
                    deepcharter$self().getPos().z(),
                    layer + " section " + sectionY + " marked " + cir.getReturnValueZ() + " ticking "
                            + (deepcharter$self().getTickingChunk() != null));
        }
    }

    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void deepcharter$traceBroadcast(LevelChunk chunk, CallbackInfo ci) {
        if (LightTrace.watching() && (deepcharter$self().hasChangesToBroadcast() || !skyBitsEmpty())) {
            LightTrace.record(
                    "SRV",
                    "BROADCAST-CALL",
                    deepcharter$self().getPos().x(),
                    deepcharter$self().getPos().z(),
                    "sky bits " + skyChangedLightSectionFilter.cardinality() + ", block bits "
                            + blockChangedLightSectionFilter.cardinality());
        }
    }

    private boolean skyBitsEmpty() {
        return skyChangedLightSectionFilter.isEmpty() && blockChangedLightSectionFilter.isEmpty();
    }

    @Inject(method = "broadcast", at = @At("HEAD"))
    private void deepcharter$traceSend(List<ServerPlayer> players, Packet<?> packet, CallbackInfo ci) {
        if (LightTrace.watching()) {
            LightTrace.record(
                    "SRV",
                    "BROADCAST-SEND",
                    deepcharter$self().getPos().x(),
                    deepcharter$self().getPos().z(),
                    packet.getClass().getSimpleName() + " to " + players.size() + " players");
        }
    }
}
