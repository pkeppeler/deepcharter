package io.github.pkeppeler.deepcharter.client.continuity.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.pkeppeler.deepcharter.client.continuity.ContinuityClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Opens the Continuity Event's screen where vanilla opens its death screen (#1337, ADR 0100). Target: the {@code Gui.setScreen} call in
 * {@code ClientPacketListener.handlePlayerCombatKill}, which vanilla reaches only for the client's own player when the death screen is
 * shown; with {@code doImmediateRespawn} it respawns at once instead, and so does this. Vanilla's {@code DeathScreen}, built as the
 * argument, is never shown.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerCombatKillMixin {
    @WrapOperation(
            method = "handlePlayerCombatKill(Lnet/minecraft/network/protocol/game/ClientboundPlayerCombatKillPacket;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void deepcharter$continuityScreen(Gui gui, Screen deathScreen, Operation<Void> original) {
        LocalPlayer player = Minecraft.getInstance().player;
        original.call(gui, player != null ? ContinuityClient.screenFor(player) : deathScreen);
    }
}
