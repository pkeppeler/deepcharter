package io.github.pkeppeler.deepcharter.test.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For {@code ForceSendProbeTest}: clears vanilla's 200-tick cooldown between force-sends, so its check logs at once. */
@Mixin(value = ServerGamePacketListenerImpl.class, remap = false)
public interface ServerGamePacketListenerAccess {
    @Accessor("tickCount")
    int deepcharter$tickCount();

    @Accessor("lastSupportedBlocksSend")
    void deepcharter$setLastSupportedBlocksSend(int tick);
}
