package io.github.pkeppeler.deepcharter.ore;

import net.minecraft.world.item.Item;

/** One ore as an item: heavy, and never stacked. */
public final class OreItem extends Item {
	private final OreType type;

	OreItem(OreType type, Properties properties) {
		super(properties);
		this.type = type;
	}

	public OreType type() {
		return type;
	}
}
