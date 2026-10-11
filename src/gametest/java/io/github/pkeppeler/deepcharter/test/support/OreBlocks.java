package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import io.github.pkeppeler.deepcharter.ore.OreType;

/** The ore block of an {@link OreType}, found the way the game finds it: by its id in the block registry. */
public final class OreBlocks {
	private OreBlocks() {
	}

	public static Block of(OreType type) {
		return BuiltInRegistries.BLOCK.getValue(type.blockId());
	}
}
