package io.github.pkeppeler.deepcharter.terminal;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * A kind of terminal: its block, who may use it, and how it is repaired. Make one with {@link TerminalTypes#register} or
 * {@link TerminalTypes#registerAlwaysOnline}.
 *
 * @param id     also the id of the block and its item
 * @param access who may open it and run its actions
 * @param repair how it is repaired, or empty for a terminal that is always online: it has no offline screen and its actions run at once
 */
public record TerminalType(Identifier id, Block block, Access access, Optional<Repair> repair) {
	/** Who may use a terminal. Nothing else changes with it: distance and the repair state are checked for everyone. */
	public enum Access {
		/** Only a player on a charter. */
		CHARTER_ONLY,
		/** Any player, charter or not: the contract terminal, where a player founds a charter, works this way. */
		ANYONE
	}

	/**
	 * What repairs a terminal.
	 *
	 * @param parts        the items inserted to repair it, at least one; each is a part of this type only
	 * @param prerequisite the type whose repair comes first, or empty for the first in a chain
	 */
	public record Repair(List<Item> parts, Optional<TerminalType> prerequisite) {
		public Repair {
			parts = List.copyOf(parts);
		}
	}

	public boolean needsRepair() {
		return repair.isPresent();
	}

	/** The parts that repair it, empty when it needs no repair. */
	public List<Item> parts() {
		return repair.map(Repair::parts).orElse(List.of());
	}

	public Optional<TerminalType> prerequisite() {
		return repair.flatMap(Repair::prerequisite);
	}

	/** The id of {@code part} in the item registry, which is how the saved repair state names it. */
	static Identifier partId(Item part) {
		return BuiltInRegistries.ITEM.getKey(part);
	}
}
