package io.github.pkeppeler.deepcharter.texture;

import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Block-state properties that a blockstate file reads to pick a block's texture layers (docs/design/skins.md). */
public final class TextureProperties {
	/**
	 * The block is working: a terminal that is online, a lamp that is lit. Its blockstate file maps it to the model with the
	 * glow and animated layers; the other state shows the dark one.
	 */
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

	private TextureProperties() {
	}
}
