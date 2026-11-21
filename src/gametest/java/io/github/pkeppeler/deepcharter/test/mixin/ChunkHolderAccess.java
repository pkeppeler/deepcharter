package io.github.pkeppeler.deepcharter.test.mixin;

import java.util.BitSet;
import net.minecraft.server.level.ChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code StructureLightBorderTest} reads which sections of a holder are marked for a block light update. */
@Mixin(value = ChunkHolder.class, remap = false)
public interface ChunkHolderAccess {
    @Accessor("blockChangedLightSectionFilter")
    BitSet deepcharter$blockChangedLightSectionFilter();
}
