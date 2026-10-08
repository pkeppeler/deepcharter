package io.github.pkeppeler.deepcharter.handbook;

import net.minecraft.world.item.Item;

/** The Employee Handbook. It is bound to its holder: see {@link HandbookItems}. */
public final class HandbookItem extends Item {
	HandbookItem(Properties properties) {
		super(properties);
	}

	/** A bundle or shulker box refuses it, so the handbook cannot be stored inside one. */
	@Override
	public boolean canFitInsideContainerItems() {
		return false;
	}
}
