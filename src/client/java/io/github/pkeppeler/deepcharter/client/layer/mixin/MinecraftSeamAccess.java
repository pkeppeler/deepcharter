package io.github.pkeppeler.deepcharter.client.layer.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches {@code Minecraft}'s private {@code updateLevelInEngines(ClientLevel, boolean)}, which points the level
 * extractor, the particle engine and the game renderer at a level (#1229, ADR 0089). Its second parameter says whether
 * to stop every sound first: a swap passes false, which is the whole point of it. Matched by name and by the descriptor
 * of the method here.
 */
@Mixin(Minecraft.class)
public interface MinecraftSeamAccess {
    @Invoker("updateLevelInEngines")
    void deepcharter$updateLevelInEngines(ClientLevel level, boolean stopSound);
}
