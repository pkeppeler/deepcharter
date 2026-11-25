package io.github.pkeppeler.deepcharter.test.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * For {@code ColumnCost}: the ticks until the next autosave, which a measured run pushes past its end, because the autosave's chunk
 * writes would land in whichever reading is open. {@code setAutoSave(false)} is no way out: it sets {@code noSave}, under which
 * {@code ChunkMap} stops unloading chunks, and a measured run waits for each region to unload.
 */
@Mixin(value = MinecraftServer.class, remap = false)
public interface MinecraftServerAccess {
    @Accessor("ticksUntilAutosave")
    int deepcharter$ticksUntilAutosave();

    @Accessor("ticksUntilAutosave")
    void deepcharter$setTicksUntilAutosave(int ticks);
}
