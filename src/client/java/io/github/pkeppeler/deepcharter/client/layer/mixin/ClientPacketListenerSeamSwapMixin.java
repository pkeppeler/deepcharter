package io.github.pkeppeler.deepcharter.client.layer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The carried window of a swap (#1229, ADR 0089): for a short while after the swap the server sends the packets of the
 * level the vehicle left, which remove the entities it carries, and then those of the level it joined, which add them
 * again. The client holds them already, so the removal of one it waits to see added is dropped, and the add of one it
 * holds is a position sync and nothing else. Targets, each by its 26.3 descriptor: {@code
 * handleAddEntity(ClientboundAddEntityPacket)V}, at its head after the thread check, and the {@code entityIds()} call
 * of {@code handleRemoveEntities(ClientboundRemoveEntitiesPacket)V}.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerSeamSwapMixin {
    @Shadow
    private ClientLevel level;

    @Inject(
            method = "handleAddEntity(Lnet/minecraft/network/protocol/game/ClientboundAddEntityPacket;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                            shift = At.Shift.AFTER),
            cancellable = true)
    private void deepcharter$pairCarried(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (SeamClient.pairCarried(level, packet)) {
            ci.cancel();
        }
    }

    @WrapOperation(
            method = "handleRemoveEntities(Lnet/minecraft/network/protocol/game/ClientboundRemoveEntitiesPacket;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/game/ClientboundRemoveEntitiesPacket;entityIds()Lit/unimi/dsi/fastutil/ints/IntList;"))
    private IntList deepcharter$keepCarried(ClientboundRemoveEntitiesPacket packet, Operation<IntList> original) {
        return SeamClient.withoutCarried(original.call(packet));
    }
}
