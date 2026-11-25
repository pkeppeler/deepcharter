package io.github.pkeppeler.deepcharter.client.layer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.client.layer.SeamClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a respawn into the dimension the server prepared adopt the background level in place of building a new one (#1185, ADR 0089).
 * Target: {@code ClientPacketListener.handleRespawn}. A claim is made after {@code ensureRunningOnSameThread}, so on the client thread
 * only; the two {@code new} expressions of a dimension change, the level's data and the level, return what the claim holds; the end of
 * the method tells the server when nothing was adopted into a dimension it prepared. The rest of the method runs unchanged.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerRespawnMixin {
    @Inject(
            method = "handleRespawn(Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                            shift = At.Shift.AFTER))
    private void deepcharter$claimBackground(ClientboundRespawnPacket packet, CallbackInfo ci) {
        SeamClient.claim(packet.commonPlayerSpawnInfo(), Minecraft.getInstance().player);
    }

    @WrapOperation(
            method = "handleRespawn(Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;)V",
            at = @At(value = "NEW", target = "net/minecraft/client/multiplayer/ClientLevel$ClientLevelData"))
    private ClientLevel.ClientLevelData deepcharter$adoptLevelData(
            Difficulty difficulty, boolean hardcore, boolean flat, Operation<ClientLevel.ClientLevelData> original) {
        ClientLevel adopting = SeamClient.adopting();
        return adopting != null ? adopting.getLevelData() : original.call(difficulty, hardcore, flat);
    }

    @WrapOperation(
            method = "handleRespawn(Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;)V",
            at = @At(value = "NEW", target = "net/minecraft/client/multiplayer/ClientLevel"))
    private ClientLevel deepcharter$adoptLevel(
            ClientPacketListener connection,
            ClientLevel.ClientLevelData levelData,
            ResourceKey<Level> dimension,
            Holder<DimensionType> dimensionType,
            int serverChunkRadius,
            int serverSimulationDistance,
            LevelExtractor levelExtractor,
            boolean isDebug,
            long biomeZoomSeed,
            int seaLevel,
            Operation<ClientLevel> original) {
        ClientLevel adopting = SeamClient.adopting();
        if (adopting == null) {
            return original.call(
                    connection,
                    levelData,
                    dimension,
                    dimensionType,
                    serverChunkRadius,
                    serverSimulationDistance,
                    levelExtractor,
                    isDebug,
                    biomeZoomSeed,
                    seaLevel);
        }
        adopting.getChunkSource().updateViewRadius(serverChunkRadius);
        adopting.setServerSimulationDistance(serverSimulationDistance);
        return adopting;
    }

    @Inject(
            method = "handleRespawn(Lnet/minecraft/network/protocol/game/ClientboundRespawnPacket;)V",
            at = @At("RETURN"))
    private void deepcharter$endClaim(ClientboundRespawnPacket packet, CallbackInfo ci) {
        SeamClient.endClaim(packet.commonPlayerSpawnInfo().dimension());
        BreachEffects.respawned();
    }
}
