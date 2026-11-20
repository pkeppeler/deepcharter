package io.github.pkeppeler.deepcharter.client.colony.mixin;

import io.github.pkeppeler.deepcharter.client.colony.OldWorldRefusal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops a singleplayer world of an old world format before its server starts, and shows a message in place of a crash report (ADR 0059). */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    @Inject(method = "openWorldDoLoad", at = @At("HEAD"), cancellable = true)
    private void deepcharter$refuseOldWorld(
            LevelStorageAccess access, WorldStem worldStem, PackRepository packRepository, CallbackInfo ci) {
        if (OldWorldRefusal.refuse(Minecraft.getInstance(), access, worldStem)) {
            ci.cancel();
        }
    }
}
