package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.ore.OreType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/** The ore block of an {@link OreType}, found the way the game finds it: by its id in the block registry. */
public final class OreBlocks {
    private OreBlocks() {}

    /** @throws IllegalStateException if the registry has no block with the ore's id: the registry would answer air, and a test would pass vacuously */
    public static Block of(OreType type) {
        if (!BuiltInRegistries.BLOCK.containsKey(type.blockId())) {
            throw new IllegalStateException("no block " + type.blockId() + " is registered for " + type);
        }
        return BuiltInRegistries.BLOCK.getValue(type.blockId());
    }
}
