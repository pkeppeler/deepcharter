package io.github.pkeppeler.deepcharter.terminal;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * A kind of terminal: its block, the parts that repair it, and the terminal that must be repaired before its parts can go in.
 * Make one with {@link TerminalTypes#register}.
 *
 * @param id           also the id of the block and its item
 * @param parts        the items inserted to repair it; each is a part of this type only
 * @param prerequisite the type whose repair comes first, or empty for the first in a chain
 */
public record TerminalType(Identifier id, Block block, List<Item> parts, Optional<TerminalType> prerequisite) {
	public TerminalType {
		parts = List.copyOf(parts);
	}

	/** The id of {@code part} in the item registry, which is how the saved repair state names it. */
	static Identifier partId(Item part) {
		return BuiltInRegistries.ITEM.getKey(part);
	}
}
