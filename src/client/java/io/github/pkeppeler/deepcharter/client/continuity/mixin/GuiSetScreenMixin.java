package io.github.pkeppeler.deepcharter.client.continuity.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.pkeppeler.deepcharter.client.continuity.ContinuityClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every vanilla death screen becomes the Continuity Event's (#1337, ADR 0100). Target: {@code Gui.setScreen(Screen)}, at its head for a
 * death screen it is given ({@code ClientPacketListener.handlePlayerCombatKill}, or any other caller), and at the {@code new DeathScreen}
 * it builds itself: a screen cleared while the player is dead and shown the death screen ({@code isDeadOrDying} and {@code
 * shouldShowDeathScreen}) is replaced by {@code new DeathScreen(null, hardcore, player)}, which happens whenever a container the server
 * closes, or any screen over the sequence, goes away. Both hand back the live sequence ({@link ContinuityClient#screenFor}), so it plays on
 * where it was. With {@code doImmediateRespawn} vanilla builds no death screen and respawns at once, and so does this.
 */
@Mixin(Gui.class)
public abstract class GuiSetScreenMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @ModifyVariable(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"), argsOnly = true)
    private @Nullable Screen deepcharter$givenDeathScreen(@Nullable Screen screen) {
        return continuity(screen);
    }

    @ModifyExpressionValue(
            method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V",
            at = @At(value = "NEW", target = "net/minecraft/client/gui/screens/DeathScreen"))
    private Screen deepcharter$builtDeathScreen(Screen built) {
        LocalPlayer player = minecraft.player;
        return player != null ? ContinuityClient.screenFor(player) : built;
    }

    private @Nullable Screen continuity(@Nullable Screen screen) {
        LocalPlayer player = minecraft.player;
        return screen instanceof DeathScreen && player != null ? ContinuityClient.screenFor(player) : screen;
    }
}
